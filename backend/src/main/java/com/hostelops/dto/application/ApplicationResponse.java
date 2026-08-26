package com.hostelops.dto.application;

import com.hostelops.domain.ApplicationStatus;
import java.time.Instant;

/**
 * An application, as both students and wardens see it.
 *
 * <p>{@code decidedBy} is the deciding warden's display name, not their id: a
 * student is told who handled their application, and no client needs a user id it
 * could not use for anything anyway.
 *
 * @param decidedAt null while the application is pending
 * @param note      the warden's reason, and for a rejection the student's explanation
 */
public record ApplicationResponse(
        Long id,
        Long studentId,
        String rollNumber,
        String fullName,
        ApplicationStatus status,
        Instant appliedAt,
        Instant decidedAt,
        String decidedBy,
        String note) {
}
