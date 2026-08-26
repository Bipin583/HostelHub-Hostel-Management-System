package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hostelops.domain.Allocation;
import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.Role;
import com.hostelops.domain.Room;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.allocation.AllocationResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.AllocationMapper;
import com.hostelops.mapper.RoomMapper;
import com.hostelops.mapper.StudentMapper;
import com.hostelops.repository.AllocationRepository;
import com.hostelops.repository.RoomRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.CurrentUserProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Business rules of allocation, in isolation.
 *
 * <p>These are unit tests: they prove the decisions the service makes. They
 * cannot prove the concurrency behaviour, because a mock has no locks -- that is
 * {@code AllocationConcurrencyIT}'s job against a real Postgres. What they
 * <em>can</em> prove is the ordering the locking depends on, which
 * {@link #locksTheRoomBeforeCountingOccupancy()} does.
 */
@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {

    private static final long LH_WARDEN_USER_ID = 7L;

    @Mock
    private RoomRepository rooms;
    @Mock
    private AllocationRepository allocations;
    @Mock
    private StudentRepository students;
    @Mock
    private UserAccountRepository users;
    @Mock
    private CurrentUserProvider currentUser;

    private AllocationService service;
    private UserAccount wardenAccount;

    @BeforeEach
    void setUp() {
        RoomMapper roomMapper = new RoomMapper();
        AllocationMapper allocationMapper =
                new AllocationMapper(new StudentMapper(roomMapper), roomMapper);
        service = new AllocationService(rooms, allocations, students, users, allocationMapper, currentUser);

        wardenAccount = account(LH_WARDEN_USER_ID, "lh_warden", Role.WARDEN, HostelScope.LH);

        // An LH warden: sees female students and LH rooms, nothing else.
        lenient().when(currentUser.scope())
                .thenReturn(new AccessScope(LH_WARDEN_USER_ID, Role.WARDEN, HostelScope.LH, null));
        lenient().when(currentUser.require()).thenReturn(new AppUserPrincipal(
                LH_WARDEN_USER_ID, "lh_warden", null, Role.WARDEN, HostelScope.LH, null, true));
        lenient().when(users.findById(LH_WARDEN_USER_ID)).thenReturn(Optional.of(wardenAccount));
    }

    @Test
    @DisplayName("locks the room row before counting occupancy, never after")
    void locksTheRoomBeforeCountingOccupancy() {
        Student student = femaleStudent(1L, 2);
        Room room = lhRoom(10L, "LH-A-201", 3, 2);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(student));
        when(rooms.findByIdAndHostelTypeIn(eq(10L), anyCollection())).thenReturn(Optional.of(room));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(rooms.findByIdForUpdate(10L)).thenReturn(Optional.of(room));
        when(allocations.countByRoomIdAndActiveTrue(10L)).thenReturn(1L);

        service.allocateManually(1L, 10L);

        // The invariant the whole design rests on. Counting first and locking second
        // would still pass every other test in this class while reintroducing the
        // double-booking window, so the order is asserted explicitly.
        InOrder order = inOrder(rooms, allocations);
        order.verify(rooms).findByIdForUpdate(10L);
        order.verify(allocations).countByRoomIdAndActiveTrue(10L);
        order.verify(allocations).saveAndFlush(any(Allocation.class));
    }

    @Test
    @DisplayName("allocates and moves the student to ALLOCATED")
    void allocatesAndAdvancesLifecycle() {
        Student student = femaleStudent(1L, 2);
        Room room = lhRoom(10L, "LH-A-201", 3, 2);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(student));
        when(rooms.findByIdAndHostelTypeIn(eq(10L), anyCollection())).thenReturn(Optional.of(room));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(rooms.findByIdForUpdate(10L)).thenReturn(Optional.of(room));
        when(allocations.countByRoomIdAndActiveTrue(10L)).thenReturn(0L);

        AllocationResponse response = service.allocateManually(1L, 10L);

        assertThat(response.room().roomName()).isEqualTo("LH-A-201");
        assertThat(response.active()).isTrue();
        assertThat(student.getAllocationStatus()).isEqualTo(AllocationStatus.ALLOCATED);
        verify(allocations).saveAndFlush(any(Allocation.class));
        verify(students).save(student);
    }

    @Test
    @DisplayName("refuses a full room with ROOM_FULL and writes nothing")
    void refusesFullRoom() {
        Student student = femaleStudent(1L, 2);
        Room room = lhRoom(10L, "LH-A-201", 3, 2);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(student));
        when(rooms.findByIdAndHostelTypeIn(eq(10L), anyCollection())).thenReturn(Optional.of(room));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(rooms.findByIdForUpdate(10L)).thenReturn(Optional.of(room));
        when(allocations.countByRoomIdAndActiveTrue(10L)).thenReturn(3L);

        assertThatThrownBy(() -> service.allocateManually(1L, 10L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.ROOM_FULL);

        verify(allocations, never()).saveAndFlush(any());
        assertThat(student.getAllocationStatus()).isEqualTo(AllocationStatus.NOT_APPLIED);
    }

    @Test
    @DisplayName("refuses a room for the wrong year of study")
    void refusesWrongYear() {
        Student secondYear = femaleStudent(1L, 2);
        Room thirdYearRoom = lhRoom(10L, "LH-A-301", 3, 3);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(secondYear));
        when(rooms.findByIdAndHostelTypeIn(eq(10L), anyCollection())).thenReturn(Optional.of(thirdYearRoom));

        assertThatThrownBy(() -> service.allocateManually(1L, 10L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ELIGIBLE);

        // Eligibility is checked before the lock: no point contending for a bed the
        // student may not have.
        verify(rooms, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("refuses a room for the wrong gender")
    void refusesWrongGender() {
        Student femaleStudent = femaleStudent(1L, 2);
        Room mensRoom = room(10L, "BH-A-201", HostelType.BH, Gender.M, 3, 2);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(femaleStudent));
        when(rooms.findByIdAndHostelTypeIn(eq(10L), anyCollection())).thenReturn(Optional.of(mensRoom));

        assertThatThrownBy(() -> service.allocateManually(1L, 10L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("refuses a student who already holds a room")
    void refusesDoubleAllocation() {
        Student student = femaleStudent(1L, 2);
        Room room = lhRoom(10L, "LH-A-201", 3, 2);
        Room heldRoom = lhRoom(11L, "LH-A-202", 3, 2);

        Allocation existing = new Allocation();
        existing.setStudent(student);
        existing.setRoom(heldRoom);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(student));
        when(rooms.findByIdAndHostelTypeIn(eq(10L), anyCollection())).thenReturn(Optional.of(room));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.allocateManually(1L, 10L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.STUDENT_ALREADY_ALLOCATED);
    }

    @Test
    @DisplayName("a student outside the warden's scope is not found")
    void studentOutsideScopeIsNotFound() {
        // The scoped finder returns empty for a male student when the caller is an
        // LH warden. The service never learns the student exists, which is the
        // point: there is no code path where it could leak one.
        when(students.findByIdAndGenderIn(eq(99L), anyCollection())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.allocateManually(99L, 10L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("auto-allocation skips a candidate that filled up before the lock")
    void autoAllocationSkipsRoomThatFilledUp() {
        Student student = femaleStudent(1L, 2);
        Room filledSinceQuery = lhRoom(10L, "LH-A-201", 3, 2);
        Room stillFree = lhRoom(11L, "LH-A-202", 3, 2);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(student));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(rooms.findEligibleRoomIdsWithFreeBeds(anyCollection(), anyString(), eq(2)))
                .thenReturn(List.of(10L, 11L));

        // Room 10 looked free when the candidate query ran, but is full by the time
        // we hold its lock. This is the ordinary outcome of losing a race, not an
        // error, so the allocator moves on.
        when(rooms.findByIdForUpdate(10L)).thenReturn(Optional.of(filledSinceQuery));
        when(allocations.countByRoomIdAndActiveTrue(10L)).thenReturn(3L);
        when(rooms.findByIdForUpdate(11L)).thenReturn(Optional.of(stillFree));
        when(allocations.countByRoomIdAndActiveTrue(11L)).thenReturn(1L);

        AllocationResponse response = service.allocateAutomatically(1L);

        assertThat(response.room().roomName()).isEqualTo("LH-A-202");
    }

    @Test
    @DisplayName("auto-allocation reports NO_ROOM_AVAILABLE when nothing matches")
    void autoAllocationWithNoCandidates() {
        Student fourthYear = femaleStudent(1L, 4);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(fourthYear));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(rooms.findEligibleRoomIdsWithFreeBeds(anyCollection(), anyString(), eq(4)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.allocateAutomatically(1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.NO_ROOM_AVAILABLE);
    }

    @Test
    @DisplayName("auto-allocation reports NO_ROOM_AVAILABLE when every candidate filled up")
    void autoAllocationWhenAllCandidatesFillUp() {
        Student student = femaleStudent(1L, 2);
        Room full = lhRoom(10L, "LH-A-201", 3, 2);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(student));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(rooms.findEligibleRoomIdsWithFreeBeds(anyCollection(), anyString(), eq(2)))
                .thenReturn(List.of(10L));
        when(rooms.findByIdForUpdate(10L)).thenReturn(Optional.of(full));
        when(allocations.countByRoomIdAndActiveTrue(10L)).thenReturn(3L);

        assertThatThrownBy(() -> service.allocateAutomatically(1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.NO_ROOM_AVAILABLE);
    }

    @Test
    @DisplayName("vacating frees the bed, keeps the row and resets the lifecycle")
    void vacatingKeepsHistory() {
        Student student = femaleStudent(1L, 2);
        student.setAllocationStatus(AllocationStatus.ALLOCATED);
        Room room = lhRoom(10L, "LH-A-201", 3, 2);

        Allocation allocation = new Allocation();
        allocation.setStudent(student);
        allocation.setRoom(room);
        allocation.setActive(true);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(student));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.of(allocation));

        service.vacate(1L);

        assertThat(allocation.isActive()).isFalse();
        assertThat(allocation.getVacatedAt()).isNotNull();
        assertThat(allocation.getVacatedBy()).isEqualTo(wardenAccount);
        assertThat(student.getAllocationStatus()).isEqualTo(AllocationStatus.NOT_APPLIED);
        verify(allocations).save(allocation);
    }

    @Test
    @DisplayName("vacating a student with no room reports STUDENT_NOT_ALLOCATED")
    void vacatingWithoutAllocation() {
        Student student = femaleStudent(1L, 2);

        when(students.findByIdAndGenderIn(eq(1L), anyCollection())).thenReturn(Optional.of(student));
        when(allocations.findByStudentIdAndActiveTrue(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.vacate(1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.STUDENT_NOT_ALLOCATED);
    }

    // ---- fixtures ----

    private static UserAccount account(Long id, String username, Role role, HostelScope scope) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setUsername(username);
        account.setFullName(username);
        account.setRole(role);
        account.setHostelScope(scope);
        account.setPasswordHash("{noop-not-used-in-this-test}");
        account.setEnabled(true);
        return account;
    }

    private static Student femaleStudent(Long id, int year) {
        Student student = new Student();
        student.setId(id);
        student.setUser(account(100L + id, "student" + id, Role.STUDENT, null));
        student.setRollNumber("21CS00" + id);
        student.setGender(Gender.F);
        student.setYearOfStudy(year);
        student.setAllocationStatus(AllocationStatus.NOT_APPLIED);
        return student;
    }

    private static Room lhRoom(Long id, String name, int capacity, int eligibleYear) {
        return room(id, name, HostelType.LH, Gender.F, capacity, eligibleYear);
    }

    private static Room room(
            Long id, String name, HostelType hostelType, Gender gender, int capacity, int eligibleYear) {
        Room room = new Room();
        room.setId(id);
        room.setRoomName(name);
        room.setHostelType(hostelType);
        room.setBlock("A");
        room.setFloor(eligibleYear);
        room.setCapacity(capacity);
        room.setEligibleYear(eligibleYear);
        room.setEligibleGender(gender);
        return room;
    }
}
