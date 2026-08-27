package com.hostelops.security;

import com.hostelops.config.JwtProperties;
import com.hostelops.domain.RefreshToken;
import com.hostelops.domain.UserAccount;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>Three properties are worth stating outright:
 *
 * <ul>
 *   <li><b>Only a hash is stored.</b> The raw token is generated, handed to the
 *       client once and forgotten. A dump of {@code refresh_tokens} yields no
 *       usable session.
 *   <li><b>Rotation.</b> Redeeming a token revokes it and links it to its
 *       successor. A client therefore holds exactly one live token at a time.
 *   <li><b>Reuse detection.</b> Presenting an already-revoked token means either a
 *       stolen copy or a client that lost the race after a rotation. Neither is
 *       recoverable from, and one of them is an attack, so every live token for
 *       that user is revoked and the whole family is invalidated. That revocation
 *       is committed through {@link RefreshTokenRevoker}, in a transaction separate
 *       from this one, because the method performing it goes on to fail.
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 bits of entropy: guessing is not a threat model, theft is. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final RefreshTokenRevoker revoker;
    private final JwtProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository tokens, RefreshTokenRevoker revoker, JwtProperties properties) {
        this.tokens = tokens;
        this.revoker = revoker;
        this.properties = properties;
    }

    /** The raw token, which exists only in this return value and the HTTP response. */
    public record IssuedToken(String rawValue, Instant expiresAt) {
    }

    @Transactional
    public IssuedToken issue(UserAccount user, String userAgent) {
        Instant now = Instant.now();
        String raw = generateRawToken();

        RefreshToken row = new RefreshToken();
        row.setUser(user);
        row.setTokenHash(sha256Hex(raw));
        row.setIssuedAt(now);
        row.setExpiresAt(now.plus(properties.refreshTokenTtl()));
        row.setUserAgent(truncate(userAgent));
        tokens.save(row);

        return new IssuedToken(raw, row.getExpiresAt());
    }

    /**
     * Redeems a token and returns its replacement.
     *
     * @return the user the token belonged to, paired with a freshly issued token
     */
    @Transactional
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID, "No refresh token was supplied");
        }
        Instant now = Instant.now();
        RefreshToken existing = tokens.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.REFRESH_TOKEN_INVALID, "This session is no longer valid. Please sign in again."));

        if (!existing.isActive(now)) {
            if (existing.getRevokedAt() != null) {
                // Someone is replaying a spent token. Assume the worst and cut the
                // whole family loose rather than leaving a possibly-stolen chain live.
                //
                // Through the revoker rather than this transaction: the throw below is
                // unchecked, so a revocation written here would be rolled back on the way
                // out and the family would stay live behind a log line saying it was cut.
                int revoked = revoker.revokeAllForUser(existing.getUser().getId(), now);
                log.warn("Refresh token reuse detected for user {}; revoked {} live session(s)",
                        existing.getUser().getId(), revoked);
            }
            throw new ApiException(
                    ErrorCode.REFRESH_TOKEN_INVALID, "This session is no longer valid. Please sign in again.");
        }

        UserAccount user = existing.getUser();
        if (!user.isEnabled()) {
            // The one place a deactivated account is caught promptly: the access
            // token's 15 minutes may still be running, but it cannot be renewed.
            // Separate transaction for the same reason as above -- the throw would
            // otherwise undo the revocation and leave the rows live.
            revoker.revokeAllForUser(user.getId(), now);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID, "This account is no longer active");
        }

        String raw = generateRawToken();
        RefreshToken successor = new RefreshToken();
        successor.setUser(user);
        successor.setTokenHash(sha256Hex(raw));
        successor.setIssuedAt(now);
        successor.setExpiresAt(now.plus(properties.refreshTokenTtl()));
        successor.setUserAgent(existing.getUserAgent());
        tokens.saveAndFlush(successor);

        existing.revoke(now, successor);
        tokens.save(existing);

        return new Rotation(user, new IssuedToken(raw, successor.getExpiresAt()));
    }

    public record Rotation(UserAccount user, IssuedToken replacement) {
    }

    /** Sign-out. Unknown tokens are ignored: logout is idempotent by definition. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(sha256Hex(rawToken))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> {
                    token.revoke(Instant.now(), null);
                    tokens.save(token);
                });
    }

    /**
     * Logout-everywhere, as an ordinary participating transaction.
     *
     * <p>Deliberately not routed through {@link RefreshTokenRevoker}: this returns normally,
     * so it has no rollback to escape, and it should share the fate of whatever administrative
     * action asked for it. A deactivation that fails halfway should not leave the sessions
     * killed.
     */
    @Transactional
    public int revokeAllForUser(Long userId) {
        return tokens.revokeAllForUser(userId, Instant.now());
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, unsalted and deliberately so.
     *
     * <p>BCrypt would be wrong here. These tokens are 256 bits of uniform random
     * data, so there is no dictionary to defend against, and lookup is by hash --
     * a per-row salt would force a table scan on every refresh.
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JLS; absent it, nothing else here is safe either.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", ex);
        }
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= 255 ? userAgent : userAgent.substring(0, 255);
    }
}
