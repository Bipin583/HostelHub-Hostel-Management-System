package com.hostelops.dto.complaint;

import com.hostelops.domain.ComplaintCategory;
import com.hostelops.domain.ComplaintStatus;
import com.hostelops.domain.ComplaintUrgency;
import com.hostelops.dto.student.StudentSummaryResponse;
import java.time.Instant;

/**
 * A complaint as the client sees it.
 *
 * <p>All three lifecycle timestamps are exposed, not just the current status, because
 * the client renders a timeline and computing "how long has this been open" from a
 * status enum is impossible. They are the same three columns the analytics query reads,
 * so a warden looking at one complaint and a warden looking at the dashboard are
 * reading the same numbers.
 */
public record ComplaintResponse(
        Long id,
        StudentSummaryResponse student,
        String title,
        String description,
        ComplaintCategory category,
        ComplaintUrgency urgency,
        ComplaintStatus status,
        Instant createdAt,
        Instant inProgressAt,
        Instant resolvedAt,
        String resolvedByName,
        String resolutionNote) {
}
