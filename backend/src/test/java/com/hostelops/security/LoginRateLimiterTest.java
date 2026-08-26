package com.hostelops.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hostelops.config.RateLimitProperties;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Login throttling -- the replacement for django-axes.
 *
 * <p>The keying is the part worth testing rather than the counting. Bucket4j can
 * be trusted to count; what is specific to this application is the choice of
 * {@code (username, IP)} as the key, and the two failure modes that choice
 * avoids: locking a known account out of its own service, and a whole campus
 * behind one NAT address locking itself out collectively.
 */
class LoginRateLimiterTest {

    private static final int CAPACITY = 5;
    private static final String IP = "203.0.113.7";

    private static LoginRateLimiter limiter(boolean enabled) {
        return new LoginRateLimiter(new RateLimitProperties(
                new RateLimitProperties.Auth(enabled, CAPACITY, CAPACITY, Duration.ofMinutes(15))));
    }

    private static void attempt(LoginRateLimiter limiter, String username, String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.checkOrThrow(username, ip);
        }
    }

    @Test
    @DisplayName("allows the configured number of attempts, then refuses")
    void throttlesAfterCapacity() {
        LoginRateLimiter limiter = limiter(true);

        assertThatCode(() -> attempt(limiter, "lh_warden", IP, CAPACITY)).doesNotThrowAnyException();

        assertThatThrownBy(() -> limiter.checkOrThrow("lh_warden", IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("tells the caller how long to wait, so a client need not guess")
    void reportsRetryAfter() {
        LoginRateLimiter limiter = limiter(true);
        attempt(limiter, "lh_warden", IP, CAPACITY);

        assertThatThrownBy(() -> limiter.checkOrThrow("lh_warden", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException refused = (ApiException) thrown;
                    // The handler turns this into a Retry-After header. Without it a
                    // client retries on a guess, and a wrong guess hits the same wall.
                    assertThat(refused.getDetails()).containsKey("retryAfterSeconds");
                    long retryAfterSeconds =
                            ((Number) refused.getDetails().get("retryAfterSeconds")).longValue();
                    assertThat(retryAfterSeconds).isGreaterThanOrEqualTo(1);
                    assertThat(refused.getMessage()).contains("Try again in");
                });
    }

    @Test
    @DisplayName("one attacker cannot lock a known account out of its own service")
    void otherAddressesAreUnaffected() {
        LoginRateLimiter limiter = limiter(true);

        // An attacker exhausts the budget for this username from their address.
        attempt(limiter, "lh_warden", "198.51.100.66", CAPACITY);
        assertThatThrownBy(() -> limiter.checkOrThrow("lh_warden", "198.51.100.66"))
                .isInstanceOf(ApiException.class);

        // The real warden, on a different address, is unaffected. Keying on username
        // alone would have handed the attacker a denial of service.
        assertThatCode(() -> limiter.checkOrThrow("lh_warden", IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one fat-fingered password does not lock out everyone behind the same address")
    void otherUsernamesFromTheSameAddressAreUnaffected() {
        LoginRateLimiter limiter = limiter(true);

        attempt(limiter, "forgetful_student", IP, CAPACITY);
        assertThatThrownBy(() -> limiter.checkOrThrow("forgetful_student", IP))
                .isInstanceOf(ApiException.class);

        // Keying on IP alone would have thrown the whole campus NAT out with them.
        assertThatCode(() -> limiter.checkOrThrow("someone_else", IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("casing the username differently does not buy a fresh budget")
    void usernameKeyIsCaseInsensitive() {
        LoginRateLimiter limiter = limiter(true);

        // Usernames are matched case-insensitively at sign-in, so the counter has to
        // be too -- otherwise the limit is bypassed by alternating capitals.
        attempt(limiter, "lh_warden", IP, CAPACITY);

        assertThatThrownBy(() -> limiter.checkOrThrow("LH_Warden", IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("a successful sign-in clears the counter")
    void resetClearsTheBudget() {
        LoginRateLimiter limiter = limiter(true);
        attempt(limiter, "lh_warden", IP, CAPACITY);

        // Called after the password check succeeds: someone who eventually
        // remembers their password should not still be serving out a penalty.
        limiter.reset("lh_warden", IP);

        assertThatCode(() -> attempt(limiter, "lh_warden", IP, CAPACITY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("resetting clears only that key")
    void resetIsScopedToOneKey() {
        LoginRateLimiter limiter = limiter(true);
        attempt(limiter, "lh_warden", IP, CAPACITY);
        attempt(limiter, "other_warden", IP, CAPACITY);

        limiter.reset("lh_warden", IP);

        assertThatCode(() -> limiter.checkOrThrow("lh_warden", IP)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkOrThrow("other_warden", IP))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("disabled by configuration means never throttled, not throttled leniently")
    void disabledNeverThrottles() {
        LoginRateLimiter limiter = limiter(false);

        // Integration tests turn this off. If "disabled" merely raised the limit,
        // a long test run would start failing for reasons unrelated to the test.
        assertThatCode(() -> attempt(limiter, "lh_warden", IP, CAPACITY * 20))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("absent configuration is treated as off rather than throwing")
    void missingConfigurationNeverThrottles() {
        LoginRateLimiter limiter = new LoginRateLimiter(new RateLimitProperties(null));

        assertThatCode(() -> attempt(limiter, "lh_warden", IP, CAPACITY * 3))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null username or address is counted, not waved through")
    void nullKeyPartsStillCount() {
        LoginRateLimiter limiter = limiter(true);

        // A request that reaches here with either part missing is malformed, but it
        // still costs an attempt: waiving the limit for it would be a free bypass.
        attempt(limiter, null, null, CAPACITY);

        assertThatThrownBy(() -> limiter.checkOrThrow(null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }
}
