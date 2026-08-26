package com.hostelops.dto;

import com.hostelops.domain.HostelScope;
import com.hostelops.domain.Role;

/**
 * A user as the client sees them.
 *
 * <p>Entities are never serialised over the API. Returning {@code UserAccount}
 * directly would ship {@code passwordHash} to every caller the first time someone
 * forgot a {@code @JsonIgnore}, and would let a lazy association decide the
 * response shape at runtime.
 */
public record UserSummaryResponse(
        Long id,
        String username,
        String fullName,
        String email,
        Role role,
        HostelScope hostelScope,
        Long studentId) {
}
