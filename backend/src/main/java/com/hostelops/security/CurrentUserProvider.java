package com.hostelops.security;

import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Reads the authenticated caller out of the security context.
 *
 * <p>Services depend on this rather than on {@code SecurityContextHolder}
 * directly, which keeps them testable with a plain stub.
 */
@Component
public class CurrentUserProvider {

    /**
     * The caller, if there is one.
     *
     * <p>Empty rather than an exception, for the two callers that legitimately run with
     * no principal: {@code AuditAspect} recording a scheduled job's work, and anything
     * else invoked from {@code SchedulingConfig}'s thread pool, which has no request and
     * therefore no security context. {@link #require()} is still what an endpoint-facing
     * service calls -- a 401 is the right answer there, and making every caller unwrap
     * an {@code Optional} would make forgetting the check the easy path.
     */
    public Optional<AppUserPrincipal> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public AppUserPrincipal require() {
        // Reaching a service method with no principal means a filter or matcher
        // is misconfigured; answer 401 rather than dereferencing null.
        return optional()
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required"));
    }

    public AccessScope scope() {
        return require().accessScope();
    }
}
