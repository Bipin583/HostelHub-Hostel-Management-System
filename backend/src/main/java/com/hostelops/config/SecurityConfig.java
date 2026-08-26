package com.hostelops.config;

import com.hostelops.security.AppUserDetailsService;
import com.hostelops.security.JwtAuthenticationFilter;
import com.hostelops.security.JwtService;
import com.hostelops.security.RestAccessDeniedHandler;
import com.hostelops.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt, via the delegating encoder so hashes carry an {@code {bcrypt}}
     * prefix.
     *
     * <p>The prefix is what makes a future move to Argon2 a rolling upgrade rather
     * than a flag day: the encoder reads whichever algorithm each stored hash
     * declares and re-encodes on next sign-in. There is exactly one credential
     * path -- no plaintext comparison, no environment-specific bypass, no
     * "default password" branch anywhere in this codebase.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AppUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // hideUserNotFoundExceptions stays at its default of true: "no such user"
        // and "wrong password" must be indistinguishable to the caller.
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtService jwtService,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource) throws Exception {

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                // CSRF protection is off, and that needs justifying rather than
                // assuming, because the refresh token *is* a cookie.
                //
                // Every endpoint except /auth/refresh and /auth/logout authenticates
                // from the Authorization header, which a browser never attaches
                // automatically, so those are structurally immune. The two cookie-bearing
                // endpoints are protected by SameSite=Strict on the cookie itself: a
                // cross-site POST simply does not carry it. And a same-site forgery
                // would gain nothing readable -- the response is JSON the attacker's
                // origin cannot read past CORS, so the worst outcome is rotating the
                // victim's own token.
                //
                // The predecessor was not in this position: it used session cookies with
                // @csrf_exempt sprinkled across the API, which is genuinely open.
                .csrf(csrf -> csrf.disable())

                // No session is ever created. There is no server-side state to fix
                // after a restart and nothing to fixate.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .authorizeHttpRequests(auth -> auth
                        // Credential exchange. Rate limited in LoginRateLimiter.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()

                        // CORS preflight carries no credentials and must not 401.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Liveness for docker-compose and CI. Details are suppressed
                        // by management.endpoint.health.show-details=never.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()

                        // API docs. Reachable only where springdoc is enabled at all --
                        // SPRINGDOC_ENABLED=false in production turns these into 404s
                        // regardless of this matcher.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()

                        // Role gates. Row-level scoping is separate and lives in
                        // AccessScope; this only decides who may reach the endpoint.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/warden/**").hasAnyRole("ADMIN", "WARDEN")
                        .requestMatchers("/api/v1/student/**").hasAnyRole("ADMIN", "STUDENT")

                        // Default deny. A new endpoint is protected the moment it exists,
                        // which is the opposite of the pattern where each view had to
                        // remember to add its own decorator.
                        .anyRequest().authenticated())

                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class)

                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }

    /**
     * CORS for the Next.js frontend.
     *
     * <p>Origins are enumerated, never wildcarded: {@code allowCredentials} is
     * required for the refresh cookie, and the spec forbids pairing that with
     * {@code *} -- a wildcard here would fail at runtime rather than merely being
     * lax.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setExposedHeaders(java.util.List.of("Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
