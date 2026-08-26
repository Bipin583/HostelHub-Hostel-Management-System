package com.hostelops.service;

import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.ApplicationStatus;
import com.hostelops.domain.HostelApplication;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.application.ApplicationResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.ApplicationMapper;
import com.hostelops.repository.HostelApplicationRepository;
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
 * The accommodation lifecycle: {@code NOT_APPLIED -> PENDING -> ALLOCATED}.
 *
 * <h2>Why approval and allocation are one transaction</h2>
 *
 * <p>{@link #approve} marks the application {@code APPROVED} and then calls
 * {@link AllocationService#allocateAutomatically} in the same transaction. If no
 * eligible room has a free bed, that call throws and the approval rolls back with
 * it -- so the system has no state in which a student has been approved but has
 * nowhere to sleep. The warden gets 409 {@code NO_ROOM_AVAILABLE} and the queue
 * still shows the application as pending, which is the truth.
 *
 * <p>The alternative -- approve now, allocate later -- needs a reconciliation job,
 * a way to see approved-but-unallocated students, and an answer to what the student
 * portal should say in the meantime. Since the allocation is available synchronously
 * there is no reason to introduce that state at all. Committing the approval and
 * letting the allocation fail separately is the version of this that produces the
 * support ticket nobody can explain.
 *
 * <p>This is also the reason {@code allocateAutomatically} is called through the
 * injected bean rather than copied here: it carries the row lock, the capacity
 * re-count and the candidate walk, and a second implementation of that logic is a
 * second chance to get the concurrency wrong.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final HostelApplicationRepository applications;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final AllocationService allocationService;
    private final ApplicationMapper applicationMapper;
    private final CurrentUserProvider currentUser;

    public ApplicationService(
            HostelApplicationRepository applications,
            StudentRepository students,
            UserAccountRepository users,
            AllocationService allocationService,
            ApplicationMapper applicationMapper,
            CurrentUserProvider currentUser) {
        this.applications = applications;
        this.students = students;
        this.users = users;
        this.allocationService = allocationService;
        this.applicationMapper = applicationMapper;
        this.currentUser = currentUser;
    }

    /**
     * A student applies for accommodation.
     *
     * <p>No student id is accepted: it comes from the token, so one student cannot
     * apply on another's behalf.
     *
     * <p>The duplicate check here is a courtesy, not the guarantee. Two taps on a
     * slow phone both pass it, and the partial unique index
     * {@code uq_applications_one_pending} rejects the second -- which
     * {@code GlobalExceptionHandler} maps to the same
     * {@code DUPLICATE_APPLICATION} code, so the client cannot tell which layer
     * caught it and does not need to.
     */
    @Transactional
    public ApplicationResponse apply() {
        Student student = requireOwnStudentRecord();

        switch (student.getAllocationStatus()) {
            case ALLOCATED -> throw new ApiException(ErrorCode.STUDENT_ALREADY_ALLOCATED,
                    "You already hold a room. Vacate it before applying again.");
            case PENDING -> throw new ApiException(ErrorCode.DUPLICATE_APPLICATION,
                    "You already have an application awaiting a decision");
            case NOT_APPLIED -> { /* the only state from which applying is meaningful */ }
        }

        HostelApplication application = new HostelApplication();
        application.setStudent(student);
        application.setStatus(ApplicationStatus.PENDING);
        applications.save(application);

        student.setAllocationStatus(AllocationStatus.PENDING);
        students.save(student);

        log.info("Student {} applied for accommodation", student.getId());
        return applicationMapper.toResponse(application);
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> myApplications() {
        Student student = requireOwnStudentRecord();
        return applications.findHistoryForStudent(student.getId()).stream()
                .map(applicationMapper::toResponse)
                .toList();
    }

    /** The warden's queue: oldest first, so nobody is left at the back forever. */
    @Transactional(readOnly = true)
    public Page<ApplicationResponse> listPending(Pageable pageable) {
        AccessScope scope = currentUser.scope();
        return applications
                .findByStatusInScope(ApplicationStatus.PENDING, scope.visibleGenders(), pageable)
                .map(applicationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ApplicationResponse> list(ApplicationStatus status, Pageable pageable) {
        AccessScope scope = currentUser.scope();
        Page<HostelApplication> page = status == null
                ? applications.findAllInScope(scope.visibleGenders(), pageable)
                : applications.findByStatusInScope(status, scope.visibleGenders(), pageable);
        return page.map(applicationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse get(Long applicationId) {
        return applicationMapper.toResponse(scopedApplication(applicationId));
    }

    /**
     * Approves an application and allocates a room, atomically.
     *
     * <p>The allocation runs under the approving warden's scope, so the room chosen
     * is necessarily one of theirs -- an LH warden cannot approve their way into a
     * BH room even by accident.
     */
    @Transactional
    public ApplicationResponse approve(Long applicationId, String note) {
        HostelApplication application = scopedApplication(applicationId);
        requirePending(application);

        application.decide(ApplicationStatus.APPROVED, actor(), Instant.now(), note);
        applications.save(application);

        // Throws NO_ROOM_AVAILABLE if nothing eligible has a free bed, and the
        // approval above rolls back with it. Also sets the student's status to
        // ALLOCATED, which is why nothing here touches it.
        allocationService.allocateAutomatically(application.getStudent().getId());

        log.info("Application {} approved and a room allocated to student {}",
                application.getId(), application.getStudent().getId());
        return applicationMapper.toResponse(application);
    }

    /**
     * Rejects an application, returning the student to the start of the lifecycle.
     *
     * <p>Back to {@code NOT_APPLIED} rather than to a {@code REJECTED} student
     * status: the rejection belongs to the application, which keeps it forever, and
     * a student whose form was incomplete must be able to apply again. Putting a
     * terminal state on the student would make "rejected once" mean "never housed".
     */
    @Transactional
    public ApplicationResponse reject(Long applicationId, String reason) {
        HostelApplication application = scopedApplication(applicationId);
        requirePending(application);

        application.decide(ApplicationStatus.REJECTED, actor(), Instant.now(), reason);
        applications.save(application);

        Student student = application.getStudent();
        student.setAllocationStatus(AllocationStatus.NOT_APPLIED);
        students.save(student);

        log.info("Application {} rejected for student {}", application.getId(), student.getId());
        return applicationMapper.toResponse(application);
    }

    private HostelApplication scopedApplication(Long applicationId) {
        AccessScope scope = currentUser.scope();
        return applications.findByIdInScope(applicationId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("application", applicationId));
    }

    /**
     * Rejects a second decision on the same application.
     *
     * <p>409 rather than a silent no-op: two wardens working the same queue should
     * be told the row moved, not left believing their click did something.
     */
    private void requirePending(HostelApplication application) {
        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "This application was already " + application.getStatus().name().toLowerCase(),
                    Map.of("currentStatus", application.getStatus().name()));
        }
    }

    private Student requireOwnStudentRecord() {
        AccessScope scope = currentUser.scope();
        Long studentId = scope.requireStudentId();
        return students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));
    }

    private UserAccount actor() {
        Long userId = currentUser.require().getUserId();
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL, "Acting account not found"));
    }
}
