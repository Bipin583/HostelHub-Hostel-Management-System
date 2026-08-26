package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.hostelops.dto.student.StudentDetailResponse;
import com.hostelops.dto.student.StudentProfileUpdateRequest;
import com.hostelops.dto.student.StudentSummaryResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.RoomMapper;
import com.hostelops.mapper.StudentMapper;
import com.hostelops.repository.AllocationRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.StudentSearchCriteria;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.CurrentUserProvider;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Student reads and the self-service profile write.
 *
 * <p>Several of these tests assert on an argument rather than on a return value.
 * That is deliberate: the interesting property of this service is not what it gives
 * back but what it passes down. A scoping bug does not produce a wrong-looking
 * response -- it produces a perfectly well-formed response containing another
 * hostel's students, which no assertion on the shape of the output would catch. So
 * the genders collection handed to the repository is what gets captured and checked.
 */
@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    private static final long WARDEN_USER_ID = 7L;
    private static final long STUDENT_USER_ID = 41L;
    private static final long STUDENT_ID = 4L;
    private static final long ROOM_ID = 90L;

    @Mock
    private StudentRepository students;
    @Mock
    private AllocationRepository allocations;
    @Mock
    private CurrentUserProvider currentUser;

    @Captor
    private ArgumentCaptor<Collection<Gender>> genders;

    private StudentService serviceFor(Role role, HostelScope hostelScope, Long studentId) {
        long userId = role == Role.STUDENT ? STUDENT_USER_ID : WARDEN_USER_ID;
        AppUserPrincipal principal = new AppUserPrincipal(
                userId, "caller", null, role, hostelScope, studentId, true);
        lenient().when(currentUser.scope()).thenReturn(principal.accessScope());
        lenient().when(currentUser.require()).thenReturn(principal);
        return new StudentService(
                students, allocations, new StudentMapper(new RoomMapper()), currentUser);
    }

    private StudentService asLadiesWarden() {
        return serviceFor(Role.WARDEN, HostelScope.LH, null);
    }

    private StudentService asAdmin() {
        return serviceFor(Role.ADMIN, null, null);
    }

    private StudentService asStudent() {
        return serviceFor(Role.STUDENT, null, STUDENT_ID);
    }

    @Nested
    @DisplayName("scoping")
    class Scoping {

        @Test
        @DisplayName("a ladies-hostel warden searches female students only")
        void wardenSearchIsNarrowedToOneGender() {
            when(students.search(any(), anyCollection(), any(Pageable.class))).thenReturn(Page.empty());

            asLadiesWarden().list(StudentSearchCriteria.UNFILTERED, PageRequest.of(0, 20));

            verify(students).search(any(), genders.capture(), any(Pageable.class));
            assertThat(genders.getValue()).containsExactly(Gender.F);
        }

        @Test
        @DisplayName("an admin searches every gender -- the widest scope, not an exemption from it")
        void adminSearchesEveryGender() {
            when(students.search(any(), anyCollection(), any(Pageable.class))).thenReturn(Page.empty());

            asAdmin().list(StudentSearchCriteria.UNFILTERED, PageRequest.of(0, 20));

            verify(students).search(any(), genders.capture(), any(Pageable.class));
            // Not "no filter": the query still has an IN clause, it just lists all the
            // values. That is what keeps the filter from being something a new query can
            // forget -- there is no code path in which the argument is absent.
            assertThat(genders.getValue()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Gender.class));
        }

        @Test
        @DisplayName("the criteria the caller supplied are passed through untouched")
        void filtersReachTheRepository() {
            when(students.search(any(), anyCollection(), any(Pageable.class))).thenReturn(Page.empty());
            StudentSearchCriteria criteria =
                    new StudentSearchCriteria(2, AllocationStatus.ALLOCATED, "  21CS  ");

            asLadiesWarden().list(criteria, PageRequest.of(0, 20));

            ArgumentCaptor<StudentSearchCriteria> captured =
                    ArgumentCaptor.forClass(StudentSearchCriteria.class);
            verify(students).search(captured.capture(), anyCollection(), any(Pageable.class));
            assertThat(captured.getValue().yearOfStudy()).isEqualTo(2);
            assertThat(captured.getValue().allocationStatus()).isEqualTo(AllocationStatus.ALLOCATED);
            // Trimmed by the record's compact constructor, so a padded search box does
            // not silently match nothing.
            assertThat(captured.getValue().query()).isEqualTo("21CS");
        }

        @Test
        @DisplayName("a student in another hostel reads as absent, not as forbidden")
        void outOfScopeStudentIsNotFound() {
            when(students.findByIdAndGenderIn(eq(99L), anyCollection())).thenReturn(Optional.empty());

            StudentService svc = asLadiesWarden();
            assertThatThrownBy(() -> svc.get(99L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));

            // 404 rather than 403 on purpose: a 403 would confirm the id exists, and a
            // warden walking the id space would learn the size and shape of the other
            // hostel's roster without reading a single record.
        }
    }

    @Nested
    @DisplayName("the caller's own record")
    class Self {

        @Test
        @DisplayName("resolves the student id from the token, not from a parameter")
        void meComesFromTheToken() {
            Student student = student(AllocationStatus.NOT_APPLIED);
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student));
            when(allocations.findByStudentIdAndActiveTrue(STUDENT_ID)).thenReturn(Optional.empty());

            StudentDetailResponse response = asStudent().me();

            assertThat(response.id()).isEqualTo(STUDENT_ID);
            assertThat(response.rollNumber()).isEqualTo("21CS0041");
            assertThat(response.currentRoom()).isNull();
        }

        @Test
        @DisplayName("reports the live room from the active allocation, never a stored column")
        void meIncludesTheCurrentRoom() {
            Student student = student(AllocationStatus.ALLOCATED);
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student));
            when(allocations.findByStudentIdAndActiveTrue(STUDENT_ID))
                    .thenReturn(Optional.of(allocation(student, room())));

            StudentDetailResponse response = asStudent().me();

            assertThat(response.currentRoom()).isNotNull();
            assertThat(response.currentRoom().roomName()).isEqualTo("LH-101");
        }

        @Test
        @DisplayName("a warden calling a student-portal route is refused before any query runs")
        void nonStudentIsForbidden() {
            StudentService svc = asLadiesWarden();

            assertThatThrownBy(svc::me)
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));

            verify(students, never()).findByIdAndGenderIn(anyLong(), anyCollection());
        }
    }

    @Nested
    @DisplayName("roommates")
    class Roommates {

        @Test
        @DisplayName("lists the other occupants and leaves the caller out")
        void excludesTheCaller() {
            Student self = student(AllocationStatus.ALLOCATED);
            Student other = otherStudent();
            Room room = room();
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(self));
            when(allocations.findByStudentIdAndActiveTrue(STUDENT_ID))
                    .thenReturn(Optional.of(allocation(self, room)));
            when(allocations.findOccupantsOfRoom(ROOM_ID))
                    .thenReturn(List.of(allocation(self, room), allocation(other, room)));

            List<StudentSummaryResponse> roommates = asStudent().roommates();

            assertThat(roommates).extracting(StudentSummaryResponse::rollNumber)
                    .containsExactly("21EC0052");
        }

        @Test
        @DisplayName("an unallocated student gets a 409, not an empty list")
        void unallocatedStudentIsAConflict() {
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student(AllocationStatus.NOT_APPLIED)));
            when(allocations.findByStudentIdAndActiveTrue(STUDENT_ID)).thenReturn(Optional.empty());

            StudentService svc = asStudent();
            assertThatThrownBy(svc::roommates)
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.STUDENT_NOT_ALLOCATED));

            // An empty list would read as "a room to yourself", which is a different and
            // much more pleasant fact than "you have no room".
        }
    }

    @Nested
    @DisplayName("profile updates")
    class ProfileUpdates {

        @Test
        @DisplayName("stores one spelling of a phone number regardless of how it was typed")
        void normalisesPhoneNumbers() {
            Student student = student(AllocationStatus.NOT_APPLIED);
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student));
            when(allocations.findByStudentIdAndActiveTrue(STUDENT_ID)).thenReturn(Optional.empty());

            asStudent().updateMyProfile(new StudentProfileUpdateRequest(
                    "+91 98765-43210", "(0091) 91234 56789", "ECE"));

            assertThat(student.getMobileNo()).isEqualTo("9876543210");
            assertThat(student.getParentMobileNo()).isEqualTo("9123456789");
            assertThat(student.getBranch()).isEqualTo("ECE");
            verify(students).save(student);
        }

        @Test
        @DisplayName("a blank parent number clears the column instead of storing an empty string")
        void blankParentNumberBecomesNull() {
            Student student = student(AllocationStatus.NOT_APPLIED);
            student.setParentMobileNo("9000000000");
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student));
            when(allocations.findByStudentIdAndActiveTrue(STUDENT_ID)).thenReturn(Optional.empty());

            asStudent().updateMyProfile(new StudentProfileUpdateRequest("9876543210", "   ", null));

            // Null and "" are the same intent from a form, and only one of them is a
            // value the CHECK constraint would have to allow.
            assertThat(student.getParentMobileNo()).isNull();
        }

        @Test
        @DisplayName("omitting the branch leaves the existing one alone")
        void absentBranchIsNotAnErasure() {
            Student student = student(AllocationStatus.NOT_APPLIED);
            student.setBranch("CSE");
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student));
            when(allocations.findByStudentIdAndActiveTrue(STUDENT_ID)).thenReturn(Optional.empty());

            asStudent().updateMyProfile(new StudentProfileUpdateRequest("9876543210", null, null));

            assertThat(student.getBranch()).isEqualTo("CSE");
        }

        @Test
        @DisplayName("the request cannot carry a year, gender or roll number to overwrite")
        void narrowRequestClosesMassAssignment() {
            Student student = student(AllocationStatus.NOT_APPLIED);
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), anyCollection()))
                    .thenReturn(Optional.of(student));
            when(allocations.findByStudentIdAndActiveTrue(STUDENT_ID)).thenReturn(Optional.empty());

            asStudent().updateMyProfile(new StudentProfileUpdateRequest("9876543210", null, "ECE"));

            // StudentProfileUpdateRequest has no field for any of these, so the only way
            // to break this test is to widen the DTO -- which is precisely the change
            // that should have to argue for itself. A student who could edit their year
            // of study would move themselves into another cohort's rooms.
            assertThat(student.getYearOfStudy()).isEqualTo(2);
            assertThat(student.getGender()).isEqualTo(Gender.F);
            assertThat(student.getRollNumber()).isEqualTo("21CS0041");
            assertThat(student.getAllocationStatus()).isEqualTo(AllocationStatus.NOT_APPLIED);
        }
    }

    private static Allocation allocation(Student student, Room room) {
        Allocation allocation = new Allocation();
        allocation.setStudent(student);
        allocation.setRoom(room);
        allocation.setActive(true);
        return allocation;
    }

    private static Room room() {
        Room room = new Room();
        room.setId(ROOM_ID);
        room.setRoomName("LH-101");
        room.setHostelType(HostelType.LH);
        room.setBlock("A");
        room.setFloor(1);
        room.setCapacity(3);
        room.setEligibleYear(2);
        room.setEligibleGender(Gender.F);
        return room;
    }

    private static Student student(AllocationStatus status) {
        Student student = new Student();
        student.setId(STUDENT_ID);
        student.setUser(account(STUDENT_USER_ID, "student4"));
        student.setRollNumber("21CS0041");
        student.setGender(Gender.F);
        student.setYearOfStudy(2);
        student.setAllocationStatus(status);
        return student;
    }

    private static Student otherStudent() {
        Student student = new Student();
        student.setId(52L);
        student.setUser(account(52L, "student52"));
        student.setRollNumber("21EC0052");
        student.setGender(Gender.F);
        student.setYearOfStudy(2);
        student.setAllocationStatus(AllocationStatus.ALLOCATED);
        return student;
    }

    private static UserAccount account(Long id, String username) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setUsername(username);
        account.setFullName(username);
        account.setEmail(username + "@example.edu");
        account.setRole(Role.STUDENT);
        account.setPasswordHash("{noop-not-used-in-this-test}");
        account.setEnabled(true);
        return account;
    }
}
