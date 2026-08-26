package com.hostelops.dto.attendance;

import com.hostelops.dto.student.StudentSummaryResponse;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A raised absence alert.
 *
 * <p>{@code ageDays} is computed server-side from {@code triggeredOn} rather than left
 * to the client. It is the number the queue is sorted by and the one a warden is
 * judged on, so two clients must not be able to disagree about it because they
 * disagree about the current date.
 */
public record AbsenceAlertResponse(
        Long id,
        StudentSummaryResponse student,
        LocalDate streakStartDate,
        Integer consecutiveDays,
        LocalDate triggeredOn,
        long ageDays,
        Instant notifiedAt,
        String acknowledgedByName,
        Instant acknowledgedAt,
        boolean acknowledged) {
}
