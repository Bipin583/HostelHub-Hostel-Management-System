package com.hostelops.security;

import com.hostelops.repository.RefreshTokenRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes every live refresh token a user holds, in a transaction of its own.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * <p>{@link RefreshTokenService#rotate} revokes a token family and then <em>throws</em>:
 * reuse detection and a deactivated account both end in {@code REFRESH_TOKEN_INVALID}.
 * {@link com.hostelops.exception.ApiException} is unchecked, so Spring's default rollback
 * rule takes the rotation transaction down -- and, written inline, the revocation with it.
 * The bulk {@code UPDATE} does reach the database, and is then rolled back on the way out,
 * leaving a possibly-stolen chain live behind a log line claiming it had been cut.
 *
 * <p>That failure is quiet in the worst way: the 401 still goes back, the error code is
 * still right, and only the durable effect is missing. Every observable part of the
 * response agrees with the intended behaviour, which is why the mock-based unit tests
 * covering these two paths passed throughout -- they verify the repository call, and the
 * repository call was being made. Only {@code AuthFlowIT}, reading the rows back after the
 * transaction had ended, could see that reuse detection was inert.
 *
 * <p>{@link Propagation#REQUIRES_NEW} puts the revocation in a transaction that commits
 * before the rotation's rollback is decided, so containment survives the refusal that
 * follows it. Note this is the mirror image of {@code FeeReminderDispatch}: there the same
 * annotation exists to <em>confine</em> a rollback to one invoice, here it exists to
 * <em>escape</em> one.
 *
 * <p>A separate bean because Spring's transaction advice lives on a proxy. A
 * {@code REQUIRES_NEW} method that {@code rotate} called on {@code this} would never cross
 * that proxy, would silently join the very transaction it needs to escape, and would
 * reinstate the bug with the annotation sitting right there implying otherwise.
 *
 * <p>No self-deadlock: {@code rotate} reaches both call sites having only {@code SELECT}ed
 * from {@code refresh_tokens}, and a plain read takes no row lock under
 * {@code READ COMMITTED}, so the suspended transaction holds nothing the new one waits on.
 */
@Component
class RefreshTokenRevoker {

    private final RefreshTokenRepository tokens;

    RefreshTokenRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * Ends every live session for one user.
     *
     * @param now the revocation timestamp, passed in rather than read here so that one
     *     detection event stamps every row it touches with a single instant
     * @return how many tokens were revoked. Committed by the time this returns, so unlike
     *     the count from an inline update it is safe to log as fact.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int revokeAllForUser(Long userId, Instant now) {
        return tokens.revokeAllForUser(userId, now);
    }
}
