package com.hostelops.dto.complaint;

import com.hostelops.domain.ComplaintCategory;
import com.hostelops.domain.ComplaintUrgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A student raising an issue.
 *
 * <p>There is no {@code studentId} field. The author is the authenticated caller, so
 * a student cannot file a complaint in someone else's name -- the absence of the field
 * is the check.
 *
 * <p>{@code status} is absent for the same reason: a complaint is always born
 * {@code OPEN}, and letting the creator name a status would let them file one as
 * already resolved.
 */
public record ComplaintCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull ComplaintCategory category,
        @NotNull ComplaintUrgency urgency) {
}
