package com.hostelops.security;

import com.hostelops.config.JwtProperties;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies the short-lived access token.
 *
 * <p>The token carries role, warden scope and student id so an authorization
 * decision costs no database round trip. Long-lived authority stays out of it
 * entirely: renewal goes through the refresh token table, which is checked
 * against the database every time.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_SCOPE = "scope";
    private static final String CLAIM_STUDENT_ID = "sid";
    private static final String CLAIM_USERNAME = "username";

    /** HS256 requires a key at least as long as its output. Shorter is rejected, not padded. */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret is not set. Provide JWT_SECRET (at least " + MIN_SECRET_BYTES
                            + " characters). There is deliberately no default: a built-in key would let "
                            + "anyone who has read the source mint an admin token.");
        }
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret must be at least " + MIN_SECRET_BYTES
                    + " bytes for HS256; got " + material.length);
        }
        this.key = Keys.hmacShaKeyFor(material);
    }

    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    public String issueAccessToken(AppUserPrincipal principal) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(principal.getUserId()))
                .claim(CLAIM_USERNAME, principal.getUsername())
                .claim(CLAIM_ROLE, principal.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())));
        if (principal.getHostelScope() != null) {
            builder.claim(CLAIM_SCOPE, principal.getHostelScope().name());
        }
        if (principal.getStudentId() != null) {
            builder.claim(CLAIM_STUDENT_ID, principal.getStudentId());
        }
        return builder.signWith(key).compact();
    }

    /**
     * Verifies a token and rebuilds the principal, or returns empty.
     *
     * <p>Every failure mode -- bad signature, expired, wrong issuer, unparseable,
     * unknown role -- collapses to {@code Optional.empty()}. The caller has no
     * decision to make between them, and telling a client which one it hit is free
     * reconnaissance.
     */
    public Optional<AppUserPrincipal> parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = Long.valueOf(claims.getSubject());
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            String rawScope = claims.get(CLAIM_SCOPE, String.class);
            HostelScope scope = rawScope == null ? null : HostelScope.valueOf(rawScope);
            Number studentId = claims.get(CLAIM_STUDENT_ID, Number.class);

            // A warden token without a scope claim would authorize against an empty
            // filter set. Refuse it rather than guess.
            if (role == Role.WARDEN && scope == null) {
                log.warn("Rejected WARDEN token for user {} with no scope claim", userId);
                return Optional.empty();
            }

            return Optional.of(new AppUserPrincipal(
                    userId,
                    claims.get(CLAIM_USERNAME, String.class),
                    null,
                    role,
                    scope,
                    studentId == null ? null : studentId.longValue(),
                    true));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected access token: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
