package com.hostelops.dto.attendance;

import com.hostelops.domain.AttendanceStatus;
import com.hostelops.dto.student.StudentSummaryResponse;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One mark on the register.
 *
 * <p>{@code markedByName} rather than a nested user object: the register shows who
 * took the roll, and shipping a full account for each of 200 rows to display one
 * string is a response three times the size for no gain.
 */
public record AttendanceResponse(
        Long id,
        StudentSummaryResponse student,
        LocalDate attendanceDate,
        AttendanceStatus status,
        String markedByName,
        Instant markedAt) {
}
