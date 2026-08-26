package com.hostelops.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hostelops.config.JwtProperties;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Access-token minting and verification.
 *
 * <p>Several of these tests craft tokens with jjwt directly rather than through
 * {@link JwtService}, because the interesting cases are the ones the service
 * would never produce: a foreign signature, a stale expiry, a warden with no
 * scope. A test that can only build well-formed tokens cannot show that
 * malformed ones are refused.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-long-enough-for-hs256-signing";
    private static final String ISSUER = "hostel-ops";

    private final JwtService service = new JwtService(properties(SECRET, Duration.ofMinutes(15)));

    private static JwtProperties properties(String secret, Duration accessTtl) {
        return new JwtProperties(
                ISSUER,
                secret,
                accessTtl,
                Duration.ofDays(30),
                new JwtProperties.RefreshCookie("hostelops_refresh", "/api/v1/auth", true, "Strict"));
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static AppUserPrincipal warden() {
        return new AppUserPrincipal(7L, "lh_warden", null, Role.WARDEN, HostelScope.LH, null, true);
    }

    private static AppUserPrincipal student() {
        return new AppUserPrincipal(11L, "asha.rao", null, Role.STUDENT, null, 42L, true);
    }

    // ---- happy path ----

    @Test
    @DisplayName("a warden's token round-trips role and hostel scope")
    void roundTripsWarden() {
        String token = service.issueAccessToken(warden());

        AppUserPrincipal parsed = service.parseAccessToken(token).orElseThrow();

        assertThat(parsed.getUserId()).isEqualTo(7L);
        assertThat(parsed.getUsername()).isEqualTo("lh_warden");
        assertThat(parsed.getRole()).isEqualTo(Role.WARDEN);
        assertThat(parsed.getHostelScope()).isEqualTo(HostelScope.LH);
        assertThat(parsed.getStudentId()).isNull();
    }

    @Test
    @DisplayName("a student's token round-trips the student row id")
    void roundTripsStudent() {
        String token = service.issueAccessToken(student());

        AppUserPrincipal parsed = service.parseAccessToken(token).orElseThrow();

        assertThat(parsed.getRole()).isEqualTo(Role.STUDENT);
        assertThat(parsed.getHostelScope()).isNull();
        // Carried in the token so a row-level self check costs no query.
        assertThat(parsed.getStudentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("the token never carries the password hash")
    void doesNotCarryTheHash() {
        AppUserPrincipal withHash =
                new AppUserPrincipal(7L, "lh_warden", "{bcrypt}$2a$10$abcdefg", Role.WARDEN,
                        HostelScope.LH, null, true);

        String token = service.issueAccessToken(withHash);

        assertThat(token).doesNotContain("bcrypt");
        assertThat(service.parseAccessToken(token).orElseThrow().getPassword()).isNull();
    }

    @Test
    void reportsItsConfiguredTtl() {
        assertThat(service.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    // ---- rejections ----

    @Test
    @DisplayName("a tampered payload is rejected, not read")
    void rejectsTamperedToken() {
        String token = service.issueAccessToken(student());
        // Corrupt the payload segment. The signature no longer matches it.
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "XY."
                + parts[2];

        assertThat(service.parseAccessToken(tampered)).isEmpty();
    }

    @Test
    @DisplayName("a token signed with another key is rejected")
    void rejectsForeignSignature() {
        String foreign = Jwts.builder()
                .issuer(ISSUER)
                .subject("7")
                .claim("username", "lh_warden")
                .claim("role", "ADMIN")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(key("a-completely-different-secret-key-value-32"))
                .compact();

        assertThat(service.parseAccessToken(foreign)).isEmpty();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpiredToken() {
        // A negative TTL puts `exp` in the past at the moment of minting.
        JwtService expiring = new JwtService(properties(SECRET, Duration.ofSeconds(-30)));

        String stale = expiring.issueAccessToken(student());

        assertThat(service.parseAccessToken(stale)).isEmpty();
    }

    @Test
    @DisplayName("a token from another issuer is rejected")
    void rejectsForeignIssuer() {
        String wrongIssuer = Jwts.builder()
                .issuer("some-other-app")
                .subject("7")
                .claim("username", "lh_warden")
                .claim("role", "ADMIN")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(key(SECRET))
                .compact();

        assertThat(service.parseAccessToken(wrongIssuer)).isEmpty();
    }

    @Test
    @DisplayName("a WARDEN token with no scope claim is refused rather than treated as unscoped")
    void rejectsWardenWithoutScope() {
        // This is the token a bug elsewhere would mint. Accepting it would run the
        // warden's queries against an empty filter set, and an empty `IN ()` filter
        // is either an error or -- worse, if someone "fixes" it by omitting the
        // clause -- every hostel. Refusing it here is the cheap end of that problem.
        String scopeless = Jwts.builder()
                .issuer(ISSUER)
                .subject("7")
                .claim("username", "lh_warden")
                .claim("role", "WARDEN")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(key(SECRET))
                .compact();

        assertThat(service.parseAccessToken(scopeless)).isEmpty();
    }

    @Test
    @DisplayName("an unknown role is rejected rather than defaulted")
    void rejectsUnknownRole() {
        String bogusRole = Jwts.builder()
                .issuer(ISSUER)
                .subject("7")
                .claim("username", "someone")
                .claim("role", "SUPERUSER")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(key(SECRET))
                .compact();

        assertThat(service.parseAccessToken(bogusRole)).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(service.parseAccessToken("not-a-jwt")).isEmpty();
        assertThat(service.parseAccessToken("")).isEmpty();
    }

    // ---- startup guards ----

    @Test
    @DisplayName("a missing secret stops construction instead of defaulting to a built-in key")
    void refusesToStartWithoutASecret() {
        // The whole point: there is no fallback key. A built-in default would let
        // anyone who has read the source mint an admin token.
        assertThatThrownBy(() -> new JwtService(properties("", Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");

        assertThatThrownBy(() -> new JwtService(properties(null, Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new JwtService(properties("   ", Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a secret shorter than HS256's output is rejected, not padded")
    void refusesShortSecret() {
        assertThatThrownBy(() -> new JwtService(properties("too-short", Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }
}
