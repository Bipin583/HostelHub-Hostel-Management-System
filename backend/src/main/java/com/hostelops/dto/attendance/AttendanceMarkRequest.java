package com.hostelops.dto.attendance;

import com.hostelops.domain.AttendanceStatus;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Marks one student for one day.
 *
 * <p>The date is supplied rather than assumed to be today, because the register is
 * routinely completed in the evening and corrected the next morning. A server-side
 * {@code LocalDate.now()} would make yesterday's missed roll call unrecordable, and
 * the service is what decides whether a given date is allowed to be marked.
 */
public record AttendanceMarkRequest(
        @NotNull Long studentId,
        @NotNull LocalDate attendanceDate,
        @NotNull AttendanceStatus status) {
}
