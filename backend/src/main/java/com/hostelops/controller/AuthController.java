package com.hostelops.controller;

import com.hostelops.config.JwtProperties;
import com.hostelops.dto.UserSummaryResponse;
import com.hostelops.dto.auth.AuthResponse;
import com.hostelops.dto.auth.LoginRequest;
import com.hostelops.security.RefreshTokenService;
import com.hostelops.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange credentials for an access token",
            description = "Rate limited per (username, client IP). Returns 429 with Retry-After when exceeded.")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        AuthService.AuthOutcome outcome = authService.login(
                request, clientIpOf(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(outcome.refreshToken()).toString())
                .body(outcome.body());
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Rotate the refresh cookie and issue a new access token",
            description = "Reads the httpOnly refresh cookie; no request body. The presented token is "
                    + "revoked and replaced, so each cookie is redeemable exactly once.")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest httpRequest) {
        AuthService.AuthOutcome outcome = authService.refresh(
                readRefreshCookie(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(outcome.refreshToken()).toString())
                .body(outcome.body());
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(summary = "Revoke the refresh token and clear the cookie",
            description = "Idempotent: an absent or already-revoked token still returns 204.")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(readRefreshCookie(httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "The authenticated user, their role and warden scope")
    public UserSummaryResponse me() {
        return authService.currentUser();
    }

    /**
     * The refresh cookie.
     *
     * <p>{@code httpOnly} keeps it out of reach of page scripts, so an XSS bug
     * cannot steal a month of renewals. The {@code path} is the auth endpoints
     * only, so it is not attached to ordinary API calls and cannot leak through a
     * logged request line. {@code SameSite=Strict} is what makes disabling CSRF
     * protection defensible: a cross-site POST does not carry this cookie at all.
     */
    private ResponseCookie refreshCookie(RefreshTokenService.IssuedToken token) {
        JwtProperties.RefreshCookie config = jwtProperties.refreshCookie();
        return ResponseCookie.from(config.name(), token.rawValue())
                .httpOnly(true)
                .secure(config.secure())
                .path(config.path())
                .sameSite(config.sameSite())
                .maxAge(jwtProperties.refreshTokenTtl())
                .build();
    }

    private ResponseCookie expiredRefreshCookie() {
        JwtProperties.RefreshCookie config = jwtProperties.refreshCookie();
        return ResponseCookie.from(config.name(), "")
                .httpOnly(true)
                .secure(config.secure())
                .path(config.path())
                .sameSite(config.sameSite())
                .maxAge(Duration.ZERO)
                .build();
    }

    /**
     * Reads the cookie by the configured name.
     *
     * <p>Done by hand rather than with {@code @CookieValue} because the name is a
     * configuration value, and an annotation attribute cannot depend on one
     * without a placeholder that fails silently if the property is renamed.
     */
    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = jwtProperties.refreshCookie().name();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * The peer address, and deliberately <em>not</em> {@code X-Forwarded-For}.
     *
     * <p>Nothing in this deployment terminates in front of the application, so any
     * forwarded header would be client-supplied. Honouring it would hand an
     * attacker a fresh rate-limit bucket per request by changing one header. If a
     * trusted reverse proxy is ever put in front, the correct fix is
     * {@code server.forward-headers-strategy=framework} plus a proxy that
     * overwrites the header -- not trusting it here.
     */
    private static String clientIpOf(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
