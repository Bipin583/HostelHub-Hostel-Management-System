package com.hostelops.dto.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A warden's rejection.
 *
 * <p>The reason is required. A rejection with no explanation generates a support
 * conversation the system could have answered itself, and the student cannot tell
 * "no rooms for your year" from "your form was incomplete" without being told.
 */
public record ApplicationRejectionRequest(@NotBlank @Size(max = 500) String reason) {
}
