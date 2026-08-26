package com.hostelops.security;

import com.hostelops.config.RateLimitProperties;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Throttles credential checks -- the replacement for django-axes.
 *
 * <p>Keyed on {@code (username, client IP)} rather than on either alone. Keying
 * on username only lets one attacker lock a known account out of its own
 * service; keying on IP only means a campus behind a single NAT address locks
 * itself out collectively the moment one person fat-fingers a password.
 *
 * <p>Applied in the service layer, before the password is ever compared, rather
 * than as a servlet filter. A filter would also throttle unparseable bodies, but
 * it would have to buffer and JSON-parse the request body to find the username,
 * and re-serving a consumed input stream to the rest of the chain is a wrapper
 * class worth avoiding for what it buys. Brute force needs a username to be
 * worth anything, and by the time binding has produced one, nothing expensive
 * has happened yet.
 *
 * <p>State is per-instance and in memory. That is honest for a single deployable:
 * behind more than one replica, or across a restart, the counter resets, so a
 * production setup moves this to the shared Bucket4j Redis backend. The interface
 * here does not change when it does.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** Bounds memory. A flood of distinct usernames must not become a leak. */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final RateLimitProperties.Auth config;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(RateLimitProperties properties) {
        this.config = properties.auth();
    }

    /** Consumes one attempt, or throws {@code RATE_LIMITED} with a retry hint. */
    public void checkOrThrow(String username, String clientIp) {
        if (config == null || !config.enabled()) {
            return;
        }
        Bucket bucket = buckets.computeIfAbsent(key(username, clientIp), ignored -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds =
                    Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
            log.info("Rate limited login attempt for '{}' from {}", username, clientIp);
            throw new ApiException(
                    ErrorCode.RATE_LIMITED,
                    "Too many sign-in attempts. Try again in " + retryAfterSeconds + " seconds.",
                    Map.of("retryAfterSeconds", retryAfterSeconds));
        }
        pruneIfCrowded();
    }

    /**
     * Clears the counter after a successful sign-in, so a user who eventually
     * remembers their password is not still throttled.
     */
    public void reset(String username, String clientIp) {
        buckets.remove(key(username, clientIp));
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(config.capacity())
                .refillGreedy(config.refillTokens(), config.refillPeriod())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Drops fully-recovered buckets when the map grows large.
     *
     * <p>A bucket back at full capacity carries no information -- its holder is
     * indistinguishable from someone who has never signed in -- so removing it
     * changes no decision.
     */
    private void pruneIfCrowded() {
        if (buckets.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        buckets.entrySet().removeIf(entry -> entry.getValue().getAvailableTokens() >= config.capacity());
    }

    private static String key(String username, String clientIp) {
        return (username == null ? "" : username.toLowerCase()) + '|' + (clientIp == null ? "" : clientIp);
    }
}
