package com.hostelops.mapper;

import com.hostelops.domain.Attendance;
import com.hostelops.dto.attendance.AttendanceResponse;
import org.springframework.stereotype.Component;

@Component
public class AttendanceMapper {

    private final StudentMapper studentMapper;

    public AttendanceMapper(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    /**
     * Requires the caller's query to have fetch-joined {@code markedBy}.
     *
     * <p>Every register listing is hundreds of rows, so a lazy load of the marking
     * warden per row is the N+1 that matters most in this codebase -- one page of a
     * day's attendance would issue a query per student. The repository's finders
     * fetch-join it for exactly this reason; this mapper does not query, so it cannot
     * paper over a finder that forgot.
     */
    public AttendanceResponse toResponse(Attendance attendance) {
        return new AttendanceResponse(
                attendance.getId(),
                studentMapper.toSummary(attendance.getStudent()),
                attendance.getAttendanceDate(),
                attendance.getStatus(),
                attendance.getMarkedBy() == null ? null : attendance.getMarkedBy().getFullName(),
                attendance.getMarkedAt());
    }
}
