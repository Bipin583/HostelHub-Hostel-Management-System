package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.ApplicationStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelApplication;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.Role;
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
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.CurrentUserProvider;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The accommodation lifecycle's rules.
 *
 * <p>The property worth testing here is not "approve sets APPROVED" -- that is a
 * field assignment. It is that approving and allocating are inseparable: the
 * service must call the allocation path, and it must not swallow that path's
 * failure. A unit test cannot observe a rollback (there is no transaction), but it
 * can prove the two things a rollback depends on: that the allocation is attempted
 * and that its exception propagates. {@code ApplicationLifecycleIT} then shows the
 * approval really is absent from the database afterwards.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    private static final long WARDEN_USER_ID = 7L;
    private static final long STUDENT_USER_ID = 41L;
    private static final long STUDENT_ID = 4L;

    @Mock
    private HostelApplicationRepository applications;
    @Mock
    private StudentRepository students;
    @Mock
    private UserAccountRepository users;
    @Mock
    private AllocationService allocationService;
    @Mock
    private CurrentUserProvider currentUser;

    private ApplicationService service;

    private ApplicationService serviceFor(AccessScope scope, AppUserPrincipal principal) {
        lenient().when(currentUser.scope()).thenReturn(scope);
        lenient().when(currentUser.require()).thenReturn(principal);
        lenient().when(users.findById(WARDEN_USER_ID))
                .thenReturn(Optional.of(account(WARDEN_USER_ID, "lh_warden", Role.WARDEN, HostelScope.LH)));
        service = new ApplicationService(
                applications, students, users, allocationService, new ApplicationMapper(), currentUser);
        return service;
    }

    private ApplicationService asWarden() {
        return serviceFor(
                new AccessScope(WARDEN_USER_ID, Role.WARDEN, HostelScope.LH, null),
                new AppUserPrincipal(WARDEN_USER_ID, "lh_warden", null, Role.WARDEN, HostelScope.LH, null, true));
    }

    private ApplicationService asStudent() {
        return serviceFor(
                new AccessScope(STUDENT_USER_ID, Role.STUDENT, null, STUDENT_ID),
                new AppUserPrincipal(STUDENT_USER_ID, "student4", null, Role.STUDENT, null, STUDENT_ID, true));
    }

    @Nested
    @DisplayName("applying")
    class Applying {

        @Test
        @DisplayName("moves the student from NOT_APPLIED to PENDING")
        void createsAPendingApplication() {
            Student student = student(AllocationStatus.NOT_APPLIED);
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student));

            ApplicationResponse response = asStudent().apply();

            assertThat(response.status()).isEqualTo(ApplicationStatus.PENDING);
            assertThat(response.rollNumber()).isEqualTo("21CS0041");
            assertThat(student.getAllocationStatus()).isEqualTo(AllocationStatus.PENDING);
            verify(applications).save(any(HostelApplication.class));
            verify(students).save(student);
        }

        @Test
        @DisplayName("takes the student id from the token, never from the caller")
        void identifiesTheApplicantFromTheToken() {
            Student student = student(AllocationStatus.NOT_APPLIED);
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student));

            asStudent().apply();

            // The id in the stub is the one from AccessScope. There is no overload of
            // apply() that accepts an id, so this is really an assertion about the
            // shape of the API: a student cannot apply on someone else's behalf
            // because there is nowhere to put the other student's id.
            verify(students).findByIdAndGenderIn(eq(STUDENT_ID), anyCollection());
        }

        @Test
        @DisplayName("refuses a second application while one is pending")
        void refusesADuplicateApplication() {
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student(AllocationStatus.PENDING)));

            ApplicationService svc = asStudent();
            assertThatThrownBy(svc::apply)
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.DUPLICATE_APPLICATION));

            verify(applications, never()).save(any());
        }

        @Test
        @DisplayName("refuses an application from a student who already holds a room")
        void refusesWhenAlreadyAllocated() {
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student(AllocationStatus.ALLOCATED)));

            ApplicationService svc = asStudent();
            assertThatThrownBy(svc::apply)
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.STUDENT_ALREADY_ALLOCATED));

            verify(applications, never()).save(any());
        }

        @Test
        @DisplayName("a warden calling the student route is refused with FORBIDDEN, not a 404")
        void refusesANonStudentAccount() {
            ApplicationService svc = asWarden();

            assertThatThrownBy(svc::apply)
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));

            // Nothing was looked up: the refusal happens before any query, so a
            // student-portal route cannot be used to probe for student ids.
            verify(students, never()).findByIdAndGenderIn(anyLong(), anyCollection());
        }
    }

    @Nested
    @DisplayName("approving")
    class Approving {

        @Test
        @DisplayName("allocates a room as part of the approval")
        void approvingAllocates() {
            HostelApplication application = pendingApplication(AllocationStatus.PENDING);
            when(applications.findByIdInScope(eq(9L), anyCollection())).thenReturn(Optional.of(application));

            ApplicationResponse response = asWarden().approve(9L, "Room confirmed");

            assertThat(response.status()).isEqualTo(ApplicationStatus.APPROVED);
            assertThat(response.decidedBy()).isEqualTo("lh_warden");
            assertThat(response.decidedAt()).isNotNull();
            assertThat(response.note()).isEqualTo("Room confirmed");

            // The decision is written first and the allocation attempted second, so
            // the allocation's failure is what rolls the decision back. Reversing
            // these would leave an allocated student with a pending application if the
            // save failed.
            InOrder order = inOrder(applications, allocationService);
            order.verify(applications).save(application);
            order.verify(allocationService).allocateAutomatically(STUDENT_ID);
        }

        @Test
        @DisplayName("lets the allocation's failure propagate rather than approving anyway")
        void approvingFailsWhenNoRoomIsAvailable() {
            HostelApplication application = pendingApplication(AllocationStatus.PENDING);
            when(applications.findByIdInScope(eq(9L), anyCollection())).thenReturn(Optional.of(application));
            when(allocationService.allocateAutomatically(STUDENT_ID)).thenThrow(
                    new ApiException(ErrorCode.NO_ROOM_AVAILABLE, "No eligible room with a free bed"));

            ApplicationService svc = asWarden();
            assertThatThrownBy(() -> svc.approve(9L, null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NO_ROOM_AVAILABLE));

            // This is the whole point of the design: the exception is not caught and
            // turned into a partial success. Catching it here -- to "at least record
            // the approval" -- is what would create an approved student with no bed.
            // The @Transactional boundary discards the in-memory change; the IT proves
            // the row is really absent afterwards.
        }

        @Test
        @DisplayName("refuses to decide an application someone else already decided")
        void refusesADoubleDecision() {
            HostelApplication application = pendingApplication(AllocationStatus.PENDING);
            application.decide(ApplicationStatus.REJECTED, account(WARDEN_USER_ID, "other_warden",
                    Role.WARDEN, HostelScope.LH), java.time.Instant.now(), "Already handled");
            when(applications.findByIdInScope(eq(9L), anyCollection())).thenReturn(Optional.of(application));

            ApplicationService svc = asWarden();
            assertThatThrownBy(() -> svc.approve(9L, null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION));

            verify(allocationService, never()).allocateAutomatically(anyLong());
        }

        @Test
        @DisplayName("an application outside the warden's hostel is a 404")
        void refusesAnOutOfScopeApplication() {
            when(applications.findByIdInScope(eq(9L), anyCollection())).thenReturn(Optional.empty());

            ApplicationService svc = asWarden();
            assertThatThrownBy(() -> svc.approve(9L, null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("rejecting")
    class Rejecting {

        @Test
        @DisplayName("returns the student to NOT_APPLIED so they can apply again")
        void rejectingResetsTheLifecycle() {
            HostelApplication application = pendingApplication(AllocationStatus.PENDING);
            when(applications.findByIdInScope(eq(9L), anyCollection())).thenReturn(Optional.of(application));

            ApplicationResponse response = asWarden().reject(9L, "Documents incomplete");

            assertThat(response.status()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(response.note()).isEqualTo("Documents incomplete");
            // Not a REJECTED student status: the rejection belongs to the application,
            // and a student rejected once must still be housable.
            assertThat(application.getStudent().getAllocationStatus())
                    .isEqualTo(AllocationStatus.NOT_APPLIED);
            verify(students).save(application.getStudent());
            verify(allocationService, never()).allocateAutomatically(anyLong());
        }

        @Test
        @DisplayName("refuses to reject an already-decided application")
        void refusesADoubleDecision() {
            HostelApplication application = pendingApplication(AllocationStatus.PENDING);
            application.decide(ApplicationStatus.APPROVED, account(WARDEN_USER_ID, "other_warden",
                    Role.WARDEN, HostelScope.LH), java.time.Instant.now(), null);
            when(applications.findByIdInScope(eq(9L), anyCollection())).thenReturn(Optional.of(application));

            ApplicationService svc = asWarden();
            assertThatThrownBy(() -> svc.reject(9L, "Too late"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION));

            verify(students, never()).save(any());
        }
    }

    private static HostelApplication pendingApplication(AllocationStatus studentStatus) {
        HostelApplication application = new HostelApplication();
        application.setId(9L);
        application.setStudent(student(studentStatus));
        application.setStatus(ApplicationStatus.PENDING);
        application.setAppliedAt(java.time.Instant.now());
        return application;
    }

    private static Student student(AllocationStatus status) {
        Student student = new Student();
        student.setId(STUDENT_ID);
        student.setUser(account(STUDENT_USER_ID, "student4", Role.STUDENT, null));
        student.setRollNumber("21CS0041");
        student.setGender(Gender.F);
        student.setYearOfStudy(2);
        student.setAllocationStatus(status);
        return student;
    }

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
}
