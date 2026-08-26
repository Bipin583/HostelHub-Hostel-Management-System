package com.hostelops.repository;

import com.hostelops.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends Repository<RefreshToken, Long> {

    RefreshToken save(RefreshToken token);

    RefreshToken saveAndFlush(RefreshToken token);

    /** Lookup is by hash: the raw token is never stored, so it cannot be searched for. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live token for a user in one statement -- logout everywhere,
     * and the containment step when a replayed token suggests theft.
     */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** Housekeeping: expired-and-revoked rows have no forensic value once stale. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
