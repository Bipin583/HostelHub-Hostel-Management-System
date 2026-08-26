package com.hostelops.service;

import com.hostelops.domain.Allocation;
import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.Room;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.allocation.AllocationResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.AllocationMapper;
import com.hostelops.repository.AllocationRepository;
import com.hostelops.repository.RoomRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room allocation.
 *
 * <h2>The race this exists to prevent</h2>
 *
 * <p>Two wardens allocate the last bed in room 12 (capacity 3, two beds taken) at
 * the same moment. Without serialisation the interleaving is:
 *
 * <pre>
 * T1: SELECT count(*) FROM allocations WHERE room_id = 12 AND active  -> 2
 * T2: SELECT count(*) FROM allocations WHERE room_id = 12 AND active  -> 2
 * T1: 2 &lt; 3, so INSERT allocation (uncommitted)
 * T2: 2 &lt; 3, so INSERT allocation (uncommitted)
 * T1: COMMIT
 * T2: COMMIT                     -> room 12 now holds 4 students in 3 beds
 * </pre>
 *
 * <p>Postgres' default READ COMMITTED isolation does not stop this. Each
 * statement sees a snapshot of committed data, and at the moment each transaction
 * counts, the other's insert is not committed yet -- so both counts are honestly
 * 2. Nothing either transaction reads is stale by the rules of the isolation
 * level; the invariant simply is not one that reading can protect. Raising the
 * level to SERIALIZABLE would catch it, at the cost of serialisation failures the
 * client must retry, on every transaction in the application rather than this one.
 *
 * <h2>What actually prevents it</h2>
 *
 * <p>{@code SELECT ... FOR UPDATE} on the room row, taken <em>before</em> the
 * count. The row lock is held to end of transaction, so T2 blocks at the lock,
 * and by the time it proceeds T1 has committed and T2's count returns 3. It
 * refuses cleanly with {@code ROOM_FULL} (409). One row is contended, for the
 * duration of one insert -- allocation of different rooms never blocks.
 *
 * <p>Locking the <em>room</em> rather than the allocations is deliberate: the
 * rows being counted do not exist yet, so there is nothing to lock there. The
 * room row is the stand-in for "the right to add a bed to this room".
 *
 * <p>Behind that sits {@code trg_allocations_capacity} in the database. It cannot
 * substitute for the lock -- being a read, it is subject to the same interleaving
 * shown above -- but it catches writes that never go through this service at all:
 * a migration, a psql session, a future endpoint that forgets to lock.
 *
 * <p>The optimistic alternative -- {@code @Version} on {@code Room}, bump it on
 * allocate, retry on {@code OptimisticLockException} -- and why it was not taken
 * are written up in {@code docs/concurrency.md}.
 *
 * <p>{@code AllocationConcurrencyIT} fires N simultaneous requests at a room with
 * fewer than N free beds and asserts exactly {@code capacity} succeed. That test
 * is the claim; this comment is only the explanation.
 */
@Service
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    private final RoomRepository rooms;
    private final AllocationRepository allocations;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final AllocationMapper allocationMapper;
    private final CurrentUserProvider currentUser;

    public AllocationService(
            RoomRepository rooms,
            AllocationRepository allocations,
            StudentRepository students,
            UserAccountRepository users,
            AllocationMapper allocationMapper,
            CurrentUserProvider currentUser) {
        this.rooms = rooms;
        this.allocations = allocations;
        this.students = students;
        this.users = users;
        this.allocationMapper = allocationMapper;
        this.currentUser = currentUser;
    }

    /**
     * Allocates a specific room chosen by a warden.
     *
     * <p>Both the student and the room are fetched through scoped finders, so an
     * LH warden naming a BH room gets a 404 for the room rather than an
     * allocation.
     */
    @Transactional
    public AllocationResponse allocateManually(Long studentId, Long roomId) {
        AccessScope scope = currentUser.scope();

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));
        Room requested = rooms.findByIdAndHostelTypeIn(roomId, scope.visibleHostelTypes())
                .orElseThrow(() -> ApiException.notFound("room", roomId));

        if (!requested.matches(student)) {
            throw new ApiException(ErrorCode.ROOM_NOT_ELIGIBLE,
                    "Room " + requested.getRoomName() + " is for year " + requested.getEligibleYear()
                            + " " + requested.getEligibleGender() + " students",
                    Map.of(
                            "roomEligibleYear", requested.getEligibleYear(),
                            "roomEligibleGender", requested.getEligibleGender().name(),
                            "studentYear", student.getYearOfStudy(),
                            "studentGender", student.getGender().name()));
        }

        requireNotAlreadyAllocated(student);

        Allocation allocation = reserveBed(student, requested.getId(), actor())
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_FULL,
                        "Room " + requested.getRoomName() + " has no free beds",
                        Map.of("roomId", requested.getId(), "capacity", requested.getCapacity())));

        return allocationMapper.toResponse(allocation);
    }

    /**
     * Allocates the best eligible room with a free bed.
     *
     * <p>The matching rule is the one carried over from the original system:
     * {@code room.eligibleGender == student.gender} and
     * {@code room.eligibleYear == student.yearOfStudy} and a free bed.
     *
     * <p>The candidate list is advisory -- a room can fill between the query and
     * the lock -- so each candidate is locked and re-counted, and a full one is
     * skipped.
     *
     * <p>Candidates are ordered by fewest free beds first, which packs rooms
     * rather than scattering students across half-empty ones. That order is
     * derived from live occupancy, so two concurrent auto-allocations can compute
     * different orders and, walking more than one candidate each, lock two rooms in
     * opposite orders. Postgres detects the cycle and aborts one transaction; the
     * resulting {@code CannotAcquireLockException} reaches
     * {@code GlobalExceptionHandler} as a retryable 409, never a 500. That is an
     * accepted tradeoff rather than an oversight: a globally stable lock order
     * (candidates sorted by id) would remove the deadlock but also remove the
     * packing behaviour, and a deadlock here needs two transactions to both find
     * their first choice full in the same instant.
     */
    @Transactional
    public AllocationResponse allocateAutomatically(Long studentId) {
        AccessScope scope = currentUser.scope();

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));

        requireNotAlreadyAllocated(student);

        List<String> hostelTypes = scope.visibleHostelTypes().stream().map(HostelType::name).toList();
        List<Long> candidates = rooms.findEligibleRoomIdsWithFreeBeds(
                hostelTypes, student.getGender().name(), student.getYearOfStudy());

        if (candidates.isEmpty()) {
            throw noRoomAvailable(student);
        }

        UserAccount actor = actor();
        for (Long candidateId : candidates) {
            var reserved = reserveBed(student, candidateId, actor);
            if (reserved.isPresent()) {
                return allocationMapper.toResponse(reserved.get());
            }
            log.debug("Room {} filled between the candidate query and the lock; trying the next", candidateId);
        }

        // Every candidate filled up while we were working through the list. This is
        // a legitimate outcome under load, not an error in the matching rule.
        throw noRoomAvailable(student);
    }

    /**
     * Takes the room's write lock, re-counts, and inserts if a bed is genuinely
     * free.
     *
     * <p>Returns empty when the room turned out to be full, so the auto-allocator
     * can move to its next candidate. A manual allocation converts that empty into
     * {@code ROOM_FULL}.
     *
     * <p>The order of the three steps is the whole point and must not be
     * rearranged: <b>lock, then count, then insert</b>. Counting before locking
     * reopens the exact window the lock exists to close.
     */
    private java.util.Optional<Allocation> reserveBed(Student student, Long roomId, UserAccount actor) {
        Room locked = rooms.findByIdForUpdate(roomId)
                .orElseThrow(() -> ApiException.notFound("room", roomId));

        long occupied = allocations.countByRoomIdAndActiveTrue(locked.getId());
        if (occupied >= locked.getCapacity()) {
            return java.util.Optional.empty();
        }

        Allocation allocation = new Allocation();
        allocation.setStudent(student);
        allocation.setRoom(locked);
        allocation.setActive(true);
        allocation.setAllocatedAt(Instant.now());
        allocation.setAllocatedBy(actor);

        // Flushed inside the method so the capacity trigger and the
        // one-active-allocation-per-student index fire here, where the failure can
        // still be translated, rather than at commit after the method has returned.
        allocations.saveAndFlush(allocation);

        student.setAllocationStatus(AllocationStatus.ALLOCATED);
        students.save(student);

        log.info("Allocated room {} to student {} ({} of {} beds now taken)",
                locked.getRoomName(), student.getId(), occupied + 1, locked.getCapacity());

        return java.util.Optional.of(allocation);
    }

    /** Releases a bed, keeping the allocation row as residency history. */
    @Transactional
    public void vacate(Long studentId) {
        AccessScope scope = currentUser.scope();

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));

        Allocation allocation = allocations.findByStudentIdAndActiveTrue(student.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.STUDENT_NOT_ALLOCATED,
                        "This student does not currently hold a room"));

        allocation.vacate(actor(), Instant.now());
        allocations.save(allocation);

        // Back to the start of the lifecycle: vacating is not a rejection, so the
        // student may apply again.
        student.setAllocationStatus(AllocationStatus.NOT_APPLIED);
        students.save(student);

        log.info("Vacated room {} for student {}", allocation.getRoom().getRoomName(), student.getId());
    }

    @Transactional(readOnly = true)
    public Page<AllocationResponse> listActive(Pageable pageable) {
        AccessScope scope = currentUser.scope();
        return allocations.findActiveInScope(scope.visibleGenders(), pageable)
                .map(allocationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public AllocationResponse currentForStudent(Long studentId) {
        AccessScope scope = currentUser.scope();
        scope.requireSelf(studentId);

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));

        return allocations.findByStudentIdAndActiveTrue(student.getId())
                .map(allocationMapper::toResponse)
                .orElseThrow(() -> new ApiException(ErrorCode.STUDENT_NOT_ALLOCATED,
                        "This student does not currently hold a room"));
    }

    @Transactional(readOnly = true)
    public List<AllocationResponse> historyForStudent(Long studentId) {
        AccessScope scope = currentUser.scope();
        scope.requireSelf(studentId);

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));

        return allocations.findHistoryForStudent(student.getId()).stream()
                .map(allocationMapper::toResponse)
                .toList();
    }

    /**
     * Rejects a second allocation early, with a readable message.
     *
     * <p>Not the real guarantee. Two concurrent requests for the same student both
     * pass this check, and the partial unique index
     * {@code uq_allocations_active_student} rejects the loser -- which
     * {@code GlobalExceptionHandler} maps back to
     * {@code STUDENT_ALREADY_ALLOCATED}, so the client sees the same code either
     * way. This check exists to make the common case a clear 409 instead of a
     * constraint violation.
     */
    private void requireNotAlreadyAllocated(Student student) {
        allocations.findByStudentIdAndActiveTrue(student.getId()).ifPresent(existing -> {
            throw new ApiException(ErrorCode.STUDENT_ALREADY_ALLOCATED,
                    "This student already holds room " + existing.getRoom().getRoomName(),
                    Map.of("roomId", existing.getRoom().getId(),
                            "roomName", existing.getRoom().getRoomName()));
        });
    }

    private ApiException noRoomAvailable(Student student) {
        return new ApiException(ErrorCode.NO_ROOM_AVAILABLE,
                "No eligible room with a free bed is available for year " + student.getYearOfStudy()
                        + " " + student.getGender() + " students",
                Map.of("yearOfStudy", student.getYearOfStudy(), "gender", student.getGender().name()));
    }

    private UserAccount actor() {
        Long userId = currentUser.require().getUserId();
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL, "Acting account not found"));
    }
}
