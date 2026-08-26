package com.hostelops.dto.allocation;

import jakarta.validation.constraints.NotNull;

/** Warden picks the room. Eligibility is still enforced server-side. */
public record ManualAllocationRequest(
        @NotNull(message = "studentId is required") Long studentId,
        @NotNull(message = "roomId is required") Long roomId) {
}
