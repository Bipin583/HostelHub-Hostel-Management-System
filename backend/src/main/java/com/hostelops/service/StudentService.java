package com.hostelops.service;

import com.hostelops.domain.Allocation;
import com.hostelops.domain.Student;
import com.hostelops.dto.student.StudentDetailResponse;
import com.hostelops.dto.student.StudentProfileUpdateRequest;
import com.hostelops.dto.student.StudentSummaryResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.StudentMapper;
import com.hostelops.repository.AllocationRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.StudentSearchCriteria;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import com.hostelops.validation.PhoneNumberValidator;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student records.
 *
 * <p>Every read here goes through a scoped finder. There is no method that returns
 * students without a gender set, and there is no method that takes a
 * "skip the filter" flag -- an admin is expressed as the widest scope, not as an
 * exemption from scoping. See {@link AccessScope}.
 */
@Service
public class StudentService {

    private final StudentRepository students;
    private final AllocationRepository allocations;
    private final StudentMapper studentMapper;
    private final CurrentUserProvider currentUser;

    public StudentService(
            StudentRepository students,
            AllocationRepository allocations,
            StudentMapper studentMapper,
            CurrentUserProvider currentUser) {
        this.students = students;
        this.allocations = allocations;
        this.studentMapper = studentMapper;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<StudentSummaryResponse> list(StudentSearchCriteria criteria, Pageable pageable) {
        AccessScope scope = currentUser.scope();
        return students.search(criteria, scope.visibleGenders(), pageable)
                .map(studentMapper::toSummary);
    }

    @Transactional(readOnly = true)
    public StudentDetailResponse get(Long studentId) {
        AccessScope scope = currentUser.scope();
        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));
        return detailOf(student);
    }

    /**
     * The caller's own record.
     *
     * <p>The student id comes from the signed token, never from the path, so there
     * is no id here to tamper with. That is why this endpoint needs no ownership
     * check at all -- the strongest form of "cannot be forgotten".
     */
    @Transactional(readOnly = true)
    public StudentDetailResponse me() {
        return detailOf(requireOwnStudentRecord());
    }

    @Transactional(readOnly = true)
    public List<StudentSummaryResponse> roommates() {
        Student self = requireOwnStudentRecord();
        Allocation mine = allocations.findByStudentIdAndActiveTrue(self.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.STUDENT_NOT_ALLOCATED,
                        "You do not currently hold a room"));

        return allocations.findOccupantsOfRoom(mine.getRoom().getId()).stream()
                .map(Allocation::getStudent)
                .filter(occupant -> !occupant.getId().equals(self.getId()))
                .map(studentMapper::toSummary)
                .toList();
    }

    /**
     * Updates the fields a student owns.
     *
     * <p>Numbers are normalised before storing, so "+91 98765-43210" and
     * "9876543210" cannot both end up in the column as different strings. The
     * validator accepts either spelling; the database only ever sees one.
     */
    @Transactional
    public StudentDetailResponse updateMyProfile(StudentProfileUpdateRequest request) {
        Student student = requireOwnStudentRecord();

        student.setMobileNo(PhoneNumberValidator.normalise(request.mobileNo()));
        student.setParentMobileNo(request.parentMobileNo() == null || request.parentMobileNo().isBlank()
                ? null
                : PhoneNumberValidator.normalise(request.parentMobileNo()));
        if (request.branch() != null && !request.branch().isBlank()) {
            student.setBranch(request.branch().trim());
        }

        students.save(student);
        return detailOf(student);
    }

    /**
     * Resolves the caller's student row.
     *
     * <p>The id comes from {@link AccessScope#requireStudentId()}, which is the one
     * place that decides what happens when a non-student account reaches a
     * student-portal route.
     */
    private Student requireOwnStudentRecord() {
        AccessScope scope = currentUser.scope();
        Long studentId = scope.requireStudentId();
        return students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));
    }

    /**
     * Assembles a detail response, resolving the live room.
     *
     * <p>Called inside the transaction because {@code student.user} is lazy and
     * {@code open-in-view} is off. Mapping in the controller would throw a
     * {@code LazyInitializationException} at the point where it is least obvious
     * why.
     */
    private StudentDetailResponse detailOf(Student student) {
        Allocation active = allocations.findByStudentIdAndActiveTrue(student.getId()).orElse(null);
        return studentMapper.toDetail(student, active);
    }
}
