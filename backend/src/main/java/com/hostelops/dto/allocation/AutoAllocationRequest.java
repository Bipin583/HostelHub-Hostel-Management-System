package com.hostelops.dto.allocation;

import jakarta.validation.constraints.NotNull;

/** The server chooses the room from the matching rule. */
public record AutoAllocationRequest(
        @NotNull(message = "studentId is required") Long studentId) {
}
