package com.hostelops.mapper;

import com.hostelops.domain.Allocation;
import com.hostelops.domain.Student;
import com.hostelops.dto.student.StudentDetailResponse;
import com.hostelops.dto.student.StudentSummaryResponse;
import org.springframework.stereotype.Component;

@Component
public class StudentMapper {

    private final RoomMapper roomMapper;

    public StudentMapper(RoomMapper roomMapper) {
        this.roomMapper = roomMapper;
    }

    /**
     * Requires an open persistence context: {@code student.user} is lazy and the
     * full name lives there. Mapping therefore happens inside the service
     * transaction, which is also why {@code spring.jpa.open-in-view} is false --
     * a view-layer lazy load is a query nobody planned for.
     */
    public StudentSummaryResponse toSummary(Student student) {
        return new StudentSummaryResponse(
                student.getId(),
                student.getRollNumber(),
                student.getUser().getFullName(),
                student.getGender(),
                student.getYearOfStudy(),
                student.getBranch(),
                student.getAllocationStatus());
    }

    /**
     * @param activeAllocation the student's live allocation, or null. Passed in
     *                         rather than looked up here, because a mapper that
     *                         issues queries turns one list response into N+1 of
     *                         them without anything at the call site changing.
     */
    public StudentDetailResponse toDetail(Student student, Allocation activeAllocation) {
        return new StudentDetailResponse(
                student.getId(),
                student.getRollNumber(),
                student.getUser().getFullName(),
                student.getUser().getEmail(),
                student.getGender(),
                student.getYearOfStudy(),
                student.getBranch(),
                student.getMobileNo(),
                student.getParentMobileNo(),
                student.getAllocationStatus(),
                activeAllocation == null ? null : roomMapper.toSummary(activeAllocation.getRoom()));
    }
}
