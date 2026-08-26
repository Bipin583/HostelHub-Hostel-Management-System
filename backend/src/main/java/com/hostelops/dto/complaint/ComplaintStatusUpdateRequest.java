package com.hostelops.dto.complaint;

import com.hostelops.domain.ComplaintStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A warden moving a complaint forward.
 *
 * <p>{@code resolutionNote} is optional here and not enforced by bean validation even
 * for {@code RESOLVED}, because "required when status is X" is a cross-field rule that
 * a per-field annotation states badly. {@code ComplaintService} enforces it, where the
 * rule can be stated once and produce a message naming the actual reason.
 */
public record ComplaintStatusUpdateRequest(
        @NotNull ComplaintStatus status,
        @Size(max = 5000) String resolutionNote) {
}
