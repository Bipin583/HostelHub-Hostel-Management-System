package com.hostelops.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hostelops.config.JwtProperties;
import com.hostelops.domain.RefreshToken;
import com.hostelops.domain.Role;
import com.hostelops.domain.UserAccount;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.repository.RefreshTokenRepository;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh token issuance, rotation and revocation.
 *
 * <p>The hashing is verified by recomputing SHA-256 in the test rather than by
 * calling into the service, which is the only way to show that what lands in the
 * repository is a digest of the token and not the token itself. That is the
 * property a database dump depends on.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final long USER_ID = 7L;
    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    @Mock
    private RefreshTokenRepository tokens;

    @Mock
    private RefreshTokenRevoker revoker;

    private RefreshTokenService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(
                "hostel-ops",
                "test-secret-long-enough-for-hs256-signing",
                Duration.ofMinutes(15),
                REFRESH_TTL,
                new JwtProperties.RefreshCookie("hostelops_refresh", "/api/v1/auth", true, "Strict"));
        service = new RefreshTokenService(tokens, revoker, properties);

        user = new UserAccount();
        user.setId(USER_ID);
        user.setUsername("lh_warden");
        user.setFullName("LH Warden");
        user.setRole(Role.ADMIN);
        user.setPasswordHash("{bcrypt}$2a$10$notusedhere");
        user.setEnabled(true);
    }

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("stores a hash, never the token itself")
        void storesOnlyAHash() {
            RefreshTokenService.IssuedToken issued = service.issue(user, "Mozilla/5.0");

            RefreshToken saved = captureSaved();
            // The property that makes a stolen refresh_tokens table useless.
            assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(issued.rawValue()));
            assertThat(saved.getTokenHash())
                    .isNotEqualTo(issued.rawValue())
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
            assertThat(saved.getUser()).isSameAs(user);
            assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(saved.getRevokedAt()).isNull();
            assertThat(saved.getReplacedBy()).isNull();
        }

        @Test
        @DisplayName("expires exactly one configured TTL after issue")
        void expiresAfterTheConfiguredTtl() {
            RefreshTokenService.IssuedToken issued = service.issue(user, null);

            RefreshToken saved = captureSaved();
            assertThat(Duration.between(saved.getIssuedAt(), saved.getExpiresAt()))
                    .isEqualTo(REFRESH_TTL);
            assertThat(issued.expiresAt()).isEqualTo(saved.getExpiresAt());
        }

        @Test
        @DisplayName("never reissues the same token")
        void mintsADistinctTokenEachTime() {
            String first = service.issue(user, null).rawValue();
            String second = service.issue(user, null).rawValue();

            // 256 bits of SecureRandom. A collision here means the generator is broken.
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("truncates an over-long user agent rather than failing the insert")
        void truncatesUserAgent() {
            // user_agent is VARCHAR(255). A browser sending more is not a reason to
            // refuse someone a session.
            service.issue(user, "x".repeat(400));

            assertThat(captureSaved().getUserAgent()).hasSize(255);
        }

        @Test
        void toleratesAMissingUserAgent() {
            service.issue(user, null);

            assertThat(captureSaved().getUserAgent()).isNull();
        }
    }

    @Nested
    @DisplayName("rotating")
    class Rotating {

        @Test
        @DisplayName("issues a successor and revokes the token that was redeemed")
        void rotatesAndRevokesPredecessor() {
            RefreshToken existing = liveToken("raw-one");
            when(tokens.findByTokenHash(sha256Hex("raw-one"))).thenReturn(Optional.of(existing));

            RefreshTokenService.Rotation rotation = service.rotate("raw-one");

            assertThat(rotation.user()).isSameAs(user);
            assertThat(rotation.replacement().rawValue()).isNotEqualTo("raw-one");
            // A client holds exactly one live token: the old one is spent the moment
            // it is redeemed, which is what makes a replay detectable at all.
            assertThat(existing.getRevokedAt()).isNotNull();
            assertThat(existing.getReplacedBy()).isNotNull();
            assertThat(existing.getReplacedBy().getTokenHash())
                    .isEqualTo(sha256Hex(rotation.replacement().rawValue()));
            verify(tokens).save(existing);
        }

        @Test
        @DisplayName("persists the successor before revoking its predecessor")
        void persistsSuccessorBeforeRevoking() {
            RefreshToken existing = liveToken("raw-one");
            when(tokens.findByTokenHash(sha256Hex("raw-one"))).thenReturn(Optional.of(existing));

            service.rotate("raw-one");

            // Both writes are in one transaction, so the order does not change what a
            // reader sees. It matters for the failure case: the successor is flushed
            // first so that a constraint violation on the new row aborts before the
            // old row has been spent, rather than leaving the client with neither.
            InOrder order = inOrder(tokens);
            order.verify(tokens).saveAndFlush(any(RefreshToken.class));
            order.verify(tokens).save(existing);
        }

        @Test
        @DisplayName("carries the user agent across so a session stays recognisable")
        void carriesUserAgentToSuccessor() {
            RefreshToken existing = liveToken("raw-one");
            existing.setUserAgent("Mozilla/5.0");
            when(tokens.findByTokenHash(sha256Hex("raw-one"))).thenReturn(Optional.of(existing));

            service.rotate("raw-one");

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(tokens).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(Duration.between(
                            captor.getValue().getIssuedAt(), captor.getValue().getExpiresAt()))
                    .isEqualTo(REFRESH_TTL);
        }

        @Test
        @DisplayName("replaying a spent token revokes every session that user holds")
        void reuseRevokesTheWholeFamily() {
            RefreshToken spent = liveToken("raw-one");
            spent.revoke(Instant.now().minusSeconds(60), null);
            when(tokens.findByTokenHash(sha256Hex("raw-one"))).thenReturn(Optional.of(spent));

            assertThatThrownBy(() -> service.rotate("raw-one"))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

            // A spent token in circulation means either a stolen copy or a client that
            // lost a rotation race. One of those is an attack and neither is
            // recoverable, so the chain is cut rather than left partly live.
            //
            // Verified on the revoker, not the repository. rotate() throws immediately
            // after this call, so the revocation only counts if it commits outside the
            // rotation transaction -- an inline tokens.revokeAllForUser would satisfy a
            // repository-level verify and still be rolled back. AuthFlowIT is what proves
            // the rows actually change; this pins which collaborator is asked.
            verify(revoker).revokeAllForUser(eq(USER_ID), any(Instant.class));
            verify(tokens, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an expired token is refused without punishing the user's other sessions")
        void expiryIsNotTreatedAsTheft() {
            RefreshToken stale = liveToken("raw-one");
            stale.setExpiresAt(Instant.now().minusSeconds(1));
            when(tokens.findByTokenHash(sha256Hex("raw-one"))).thenReturn(Optional.of(stale));

            assertThatThrownBy(() -> service.rotate("raw-one"))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

            // Ordinary expiry carries no evidence of compromise. Revoking everything
            // here would log a user out of their phone because their laptop went stale.
            verify(revoker, never()).revokeAllForUser(anyLong(), any());
            verify(tokens, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a deactivated account cannot renew, which is where deactivation bites")
        void deactivatedAccountCannotRenew() {
            user.setEnabled(false);
            RefreshToken existing = liveToken("raw-one");
            when(tokens.findByTokenHash(sha256Hex("raw-one"))).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.rotate("raw-one"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("no longer active");

            // The stateless access token runs out its 15 minutes regardless; this is
            // the checkpoint that consults the database, so it is where a disabled
            // account is actually stopped.
            verify(revoker).revokeAllForUser(eq(USER_ID), any(Instant.class));
            verify(tokens, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an unknown token is refused")
        void unknownTokenIsRefused() {
            when(tokens.findByTokenHash(sha256Hex("never-issued"))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rotate("never-issued"))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

            verify(revoker, never()).revokeAllForUser(anyLong(), any());
        }

        @Test
        @DisplayName("says the same thing whether the token is unknown or merely stale")
        void refusalRevealsNothingAboutWhy() {
            RefreshToken stale = liveToken("stale-one");
            stale.setExpiresAt(Instant.now().minusSeconds(1));
            when(tokens.findByTokenHash(sha256Hex("stale-one"))).thenReturn(Optional.of(stale));
            when(tokens.findByTokenHash(sha256Hex("never-issued"))).thenReturn(Optional.empty());

            Throwable forStale = org.assertj.core.api.Assertions
                    .catchThrowable(() -> service.rotate("stale-one"));
            Throwable forUnknown = org.assertj.core.api.Assertions
                    .catchThrowable(() -> service.rotate("never-issued"));

            // Distinguishing them would tell a holder of a guessed token whether it
            // ever existed, which is a free oracle over the token space.
            assertThat(forStale).hasMessage(forUnknown.getMessage());
        }

        @Test
        @DisplayName("a missing token never reaches the database")
        void blankTokenIsRefusedWithoutALookup() {
            assertThatThrownBy(() -> service.rotate(null))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
            assertThatThrownBy(() -> service.rotate("   "))
                    .isInstanceOf(ApiException.class);

            verifyNoInteractions(tokens);
            verifyNoInteractions(revoker);
        }
    }

    @Nested
    @DisplayName("revoking")
    class Revoking {

        @Test
        @DisplayName("signing out marks the token spent with no successor")
        void revokeMarksTheTokenSpent() {
            RefreshToken live = liveToken("raw-one");
            when(tokens.findByTokenHash(sha256Hex("raw-one"))).thenReturn(Optional.of(live));

            service.revoke("raw-one");

            assertThat(live.getRevokedAt()).isNotNull();
            // Sign-out, not rotation: there is nothing to point at.
            assertThat(live.getReplacedBy()).isNull();
            verify(tokens).save(live);
        }

        @Test
        @DisplayName("revoking twice keeps the first timestamp")
        void revokeIsIdempotent() {
            Instant firstRevocation = Instant.now().minusSeconds(600);
            RefreshToken spent = liveToken("raw-one");
            spent.revoke(firstRevocation, null);
            when(tokens.findByTokenHash(sha256Hex("raw-one"))).thenReturn(Optional.of(spent));

            service.revoke("raw-one");

            // Overwriting it would destroy the only record of when the session
            // actually ended -- which is the timestamp an audit would ask about.
            assertThat(spent.getRevokedAt()).isEqualTo(firstRevocation);
            verify(tokens, never()).save(any());
        }

        @Test
        @DisplayName("signing out with a token the server has never seen is not an error")
        void revokeUnknownTokenIsANoOp() {
            when(tokens.findByTokenHash(sha256Hex("never-issued"))).thenReturn(Optional.empty());

            service.revoke("never-issued");

            // Logout is idempotent by definition: a client clearing a stale cookie
            // should not be shown a failure.
            verify(tokens, never()).save(any());
        }

        @Test
        void revokeWithNoTokenTouchesNothing() {
            service.revoke(null);
            service.revoke("");

            verifyNoInteractions(tokens);
        }

        @Test
        @DisplayName("logging out everywhere is one statement, not a loop")
        void revokeAllDelegatesToASingleUpdate() {
            when(tokens.revokeAllForUser(eq(USER_ID), any(Instant.class))).thenReturn(3);

            assertThat(service.revokeAllForUser(USER_ID)).isEqualTo(3);
        }

        @Test
        @DisplayName("containment commits in its own transaction, in a separate bean so the proxy is crossed")
        void containmentEscapesTheRotationTransaction() throws Exception {
            Method revokeAll = RefreshTokenRevoker.class
                    .getDeclaredMethod("revokeAllForUser", Long.class, Instant.class);
            Transactional annotation = revokeAll.getAnnotation(Transactional.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

            // Neither half of this is stylistic, and both were once wrong.
            //
            // Propagation: rotate() revokes the family and then throws an unchecked
            // exception. Under REQUIRED the revocation shares that transaction and is
            // rolled back with it, so reuse detection reduces to a log line with no
            // durable effect -- a 401 goes back, the error code is right, and the stolen
            // chain stays live. AuthFlowIT caught exactly that.
            //
            // Separate bean: Spring's transaction advice lives on a proxy, so a
            // REQUIRES_NEW method declared on RefreshTokenService would be invoked on
            // `this`, never cross the proxy, and rejoin the very transaction it exists to
            // escape -- with the annotation still there suggesting otherwise.
            assertThat(revokeAll.getDeclaringClass()).isNotEqualTo(RefreshTokenService.class);
        }
    }

    // ---- fixtures ----

    private RefreshToken liveToken(String rawValue) {
        Instant now = Instant.now();
        RefreshToken token = new RefreshToken();
        token.setId(1L);
        token.setUser(user);
        token.setTokenHash(sha256Hex(rawValue));
        token.setIssuedAt(now.minusSeconds(60));
        token.setExpiresAt(now.plus(REFRESH_TTL));
        return token;
    }

    private RefreshToken captureSaved() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(captor.capture());
        return captor.getValue();
    }

    /**
     * Recomputed here on purpose. Asking the service to hash the token and
     * comparing that to what the service stored would pass even if it stored the
     * raw value.
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError("SHA-256 must be available", ex);
        }
    }
}
