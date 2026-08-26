package com.hostelops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a {@code Bearer} header into an authenticated {@link AppUserPrincipal}.
 *
 * <p>An absent, malformed or expired token is <em>not</em> an error here: the
 * filter simply leaves the context anonymous and lets the URL matchers decide.
 * That is a deliberate choice rather than laziness. {@code POST /auth/refresh} is
 * called precisely when the access token has expired, and browsers happily attach
 * the stale {@code Authorization} header to it; a filter that rejected bad tokens
 * outright would 401 the one endpoint whose job is to fix that, leaving the client
 * with no way back to a valid session.
 *
 * <p>Requests to protected endpoints without a usable token therefore fail at the
 * authorization step and come back as {@code UNAUTHENTICATED} from
 * {@link RestAuthenticationEntryPoint}.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String token = header.substring(BEARER_PREFIX.length()).trim();
            jwtService.parseAccessToken(token).ifPresent(principal -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        filterChain.doFilter(request, response);
    }
}
