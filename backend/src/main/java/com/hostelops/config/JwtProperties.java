package com.hostelops.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings.
 *
 * <p>{@code secret} has no default anywhere in the configuration. A missing
 * value fails startup (see {@code JwtService}) instead of quietly falling back
 * to a shared constant that would let anyone mint an admin token.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        RefreshCookie refreshCookie) {

    public record RefreshCookie(String name, String path, boolean secure, String sameSite) {
    }
}
