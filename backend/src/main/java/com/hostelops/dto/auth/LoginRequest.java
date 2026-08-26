package com.hostelops.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sign-in credentials.
 *
 * <p>No minimum length is enforced here. Length rules belong on account
 * creation; applying them at sign-in only tells an attacker which guesses were
 * not worth making.
 */
public record LoginRequest(
        @NotBlank(message = "Username is required")
        @Size(max = 150, message = "Username is too long")
        String username,

        @NotBlank(message = "Password is required")
        @Size(max = 200, message = "Password is too long")
        String password) {
}
