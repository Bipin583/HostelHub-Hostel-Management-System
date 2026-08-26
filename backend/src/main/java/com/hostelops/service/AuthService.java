package com.hostelops.service;

import com.hostelops.domain.Role;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.UserSummaryResponse;
import com.hostelops.dto.auth.AuthResponse;
import com.hostelops.dto.auth.LoginRequest;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.UserMapper;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.CurrentUserProvider;
import com.hostelops.security.JwtService;
import com.hostelops.security.LoginRateLimiter;
import com.hostelops.security.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in, renewal and sign-out.
 *
 * <p>Nothing here ever sees a plaintext password except as the argument it hands
 * to {@link AuthenticationManager}, and nothing logs one. There is exactly one
 * way to obtain a token: a BCrypt match against a stored hash. No environment
 * check, no seeded credential, no "if password equals" branch exists anywhere in
 * this class or below it.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final LoginRateLimiter rateLimiter;
    private final UserAccountRepository users;
    private final StudentRepository students;
    private final UserMapper userMapper;
    private final CurrentUserProvider currentUser;

    public AuthService(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            RefreshTokenService refreshTokens,
            LoginRateLimiter rateLimiter,
            UserAccountRepository users,
            StudentRepository students,
            UserMapper userMapper,
            CurrentUserProvider currentUser) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
        this.users = users;
        this.students = students;
        this.userMapper = userMapper;
        this.currentUser = currentUser;
    }

    /** The response body plus the refresh token the controller must put in a cookie. */
    public record AuthOutcome(AuthResponse body, RefreshTokenService.IssuedToken refreshToken) {
    }

    @Transactional
    public AuthOutcome login(LoginRequest request, String clientIp, String userAgent) {
        String username = request.username().trim();

        // Before the hash comparison, so a brute-force run cannot even spend our
        // BCrypt cycles.
        rateLimiter.checkOrThrow(username, clientIp);

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, request.password()));

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        rateLimiter.reset(username, clientIp);

        UserAccount account = users.findById(principal.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL, "Authenticated account vanished"));

        log.info("Sign-in succeeded for user {} ({})", account.getId(), account.getRole());
        return buildOutcome(principal, account, userAgent, null);
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token.
     *
     * <p>The principal is rebuilt from the database here rather than carried over
     * from the old token, so a role change or a warden reassignment takes effect
     * within one access-token lifetime instead of persisting for the month the
     * refresh token is valid.
     */
    @Transactional
    public AuthOutcome refresh(String rawRefreshToken, String userAgent) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        UserAccount account = rotation.user();

        Long studentId = account.getRole() == Role.STUDENT
                ? students.findByUserId(account.getId()).map(student -> student.getId()).orElse(null)
                : null;

        AppUserPrincipal principal = new AppUserPrincipal(
                account.getId(),
                account.getUsername(),
                null,
                account.getRole(),
                account.getHostelScope(),
                studentId,
                account.isEnabled());

        return buildOutcome(principal, account, userAgent, rotation.replacement());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    @Transactional(readOnly = true)
    public UserSummaryResponse currentUser() {
        AppUserPrincipal principal = currentUser.require();
        UserAccount account = users.findById(principal.getUserId())
                .orElseThrow(() -> ApiException.notFound("account", principal.getUserId()));
        return userMapper.toSummary(account, principal.getStudentId());
    }

    private AuthOutcome buildOutcome(
            AppUserPrincipal principal,
            UserAccount account,
            String userAgent,
            RefreshTokenService.IssuedToken alreadyIssued) {

        RefreshTokenService.IssuedToken refresh =
                alreadyIssued != null ? alreadyIssued : refreshTokens.issue(account, userAgent);

        String accessToken = jwtService.issueAccessToken(principal);
        UserSummaryResponse summary = userMapper.toSummary(account, principal.getStudentId());

        return new AuthOutcome(
                AuthResponse.of(accessToken, jwtService.accessTokenTtl().toSeconds(), summary),
                refresh);
    }
}
