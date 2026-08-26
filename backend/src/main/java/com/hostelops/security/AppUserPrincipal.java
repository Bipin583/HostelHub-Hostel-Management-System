package com.hostelops.security;

import com.hostelops.domain.HostelScope;
import com.hostelops.domain.Role;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated caller.
 *
 * <p>Carries the warden's {@link HostelScope} and, for students, their student
 * row id, so row-level checks never need a second lookup to find out who is
 * asking.
 *
 * <p>On an API request this is reconstructed from the JWT's claims rather than
 * read from the database. That is the deliberate stateless tradeoff: a request
 * costs no user query, and in exchange a disabled account keeps access until its
 * access token expires -- at most the 15 minute TTL. Revocation that must take
 * effect immediately goes through the refresh token table, which *is* checked
 * against the database on every refresh.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long userId;
    private final String username;
    private final transient String passwordHash;
    private final Role role;
    private final HostelScope hostelScope;
    private final Long studentId;
    private final boolean enabled;

    public AppUserPrincipal(
            Long userId,
            String username,
            String passwordHash,
            Role role,
            HostelScope hostelScope,
            Long studentId,
            boolean enabled) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.hostelScope = hostelScope;
        this.studentId = studentId;
        this.enabled = enabled;
    }

    public Long getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public HostelScope getHostelScope() {
        return hostelScope;
    }

    /** The student row this account owns, or null for admins and wardens. */
    public Long getStudentId() {
        return studentId;
    }

    public AccessScope accessScope() {
        return new AccessScope(userId, role, hostelScope, studentId);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Never let a principal stringify its way into a log line with the hash. */
    @Override
    public String toString() {
        return "AppUserPrincipal[userId=" + userId + ", role=" + role + ", scope=" + hostelScope + "]";
    }
}
