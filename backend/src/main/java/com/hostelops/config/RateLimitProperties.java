package com.hostelops.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limiting for the authentication endpoints -- the replacement for
 * django-axes.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Auth auth) {

    public record Auth(boolean enabled, int capacity, int refillTokens, Duration refillPeriod) {
    }
}
