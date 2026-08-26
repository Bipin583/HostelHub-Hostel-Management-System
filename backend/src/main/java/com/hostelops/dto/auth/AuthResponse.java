package com.hostelops.dto.auth;

import com.hostelops.dto.UserSummaryResponse;

/**
 * What a successful sign-in or refresh returns.
 *
 * <p>The refresh token is deliberately absent: it travels only in the httpOnly
 * cookie, where page JavaScript cannot read it, so an XSS bug costs at most the
 * 15 minutes left on the access token rather than a month of renewals.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserSummaryResponse user) {

    public static AuthResponse of(String accessToken, long expiresInSeconds, UserSummaryResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, user);
    }
}
