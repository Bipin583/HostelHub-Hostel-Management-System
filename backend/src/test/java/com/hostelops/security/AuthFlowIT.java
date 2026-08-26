package com.hostelops.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.config.JwtProperties;
import com.hostelops.domain.HostelScope;
import com.hostelops.support.AbstractPostgresIT;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The whole sign-in lifecycle against a real database: password check, token
 * issue, refresh rotation, reuse detection, and sign-out.
 *
 * <p>The property this class exists to prove is that a refresh token is
 * single-use. Rotation on its own is only half of the defence -- it narrows the
 * window in which a stolen token is useful, but it does nothing if the spent
 * token still works. Detecting the replay and cutting the whole token family
 * loose is what turns theft into a session both parties lose, which is a state
 * the legitimate user notices and can act on. A silently shared session is not.
 *
 * <p>Everything here goes over HTTP rather than calling the service, because
 * half the behaviour under test lives in the transport: the {@code Set-Cookie}
 * attributes, the absence of the refresh token from the response body, and the
 * fact that the refresh endpoint reads a cookie rather than a parameter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIT extends AbstractPostgresIT {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/auth/me";

    /**
     * Chosen here rather than in a fixture: the point is that authentication runs a
     * real BCrypt comparison against a hash this test never sees. Nothing in the
     * application has a second way in, so a password that only exists in this
     * variable is the only credential these tests can use.
     */
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JwtProperties jwtProperties;

    @Nested
    @DisplayName("signing in")
    class SigningIn {

        @Test
        @DisplayName("a correct password returns a bearer token and sets the refresh cookie")
        void successfulLogin() throws Exception {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);

            ResponseEntity<String> response = login(warden.username(), PASSWORD);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = json.readTree(response.getBody());
            assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
            assertThat(body.path("accessToken").asText()).isNotBlank();
            assertThat(body.path("expiresInSeconds").asLong())
                    .isEqualTo(jwtProperties.accessTokenTtl().toSeconds());
            assertThat(body.path("user").path("username").asText()).isEqualTo(warden.username());
            assertThat(body.path("user").path("role").asText()).isEqualTo("WARDEN");
            assertThat(body.path("user").path("hostelScope").asText()).isEqualTo("LH");

            assertThat(refreshTokenIn(response)).isNotBlank();
        }

        @Test
        @DisplayName("the refresh token is in the cookie and nowhere in the response body")
        void refreshTokenIsNotInTheBody() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);

            ResponseEntity<String> response = login(warden.username(), PASSWORD);

            String refreshToken = refreshTokenIn(response);
            // If it appeared in the body too, any XSS on the frontend could read it out
            // of wherever the client chose to keep it -- which defeats the entire reason
            // for httpOnly. The cookie is the only copy the client ever holds.
            assertThat(response.getBody()).doesNotContain(refreshToken);
        }

        @Test
        @DisplayName("the refresh cookie is httpOnly, SameSite and scoped to the auth path")
        void cookieAttributesAreRestrictive() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);

            String setCookie = setCookieHeaderIn(login(warden.username(), PASSWORD));

            assertThat(setCookie).contains("HttpOnly");
            assertThat(setCookie).contains("SameSite=" + jwtProperties.refreshCookie().sameSite());
            // Path-scoped to /api/v1/auth: the cookie is not attached to any other
            // request, so the ordinary API surface never carries a credential capable
            // of minting new tokens.
            assertThat(setCookie).contains("Path=" + jwtProperties.refreshCookie().path());
        }

        @Test
        @DisplayName("a wrong password is a 401 that sets no cookie and says nothing useful")
        void wrongPasswordIsRejected() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);

            ResponseEntity<String> response = login(warden.username(), PASSWORD + "!");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(response)).isEqualTo("INVALID_CREDENTIALS");
            assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        }

        @Test
        @DisplayName("an unknown username fails the same way a wrong password does")
        void unknownUserIsIndistinguishable() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);

            ResponseEntity<String> unknownUser = login("no_such_warden_" + nextSequence(), PASSWORD);
            ResponseEntity<String> wrongPassword = login(warden.username(), "not the password");

            // Same status and same code. A different response for "no such user" turns
            // the login form into a username oracle: an attacker learns which accounts
            // exist before spending a single guess on a password.
            assertThat(unknownUser.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
            assertThat(errorCodeOf(unknownUser)).isEqualTo(errorCodeOf(wrongPassword));
        }

        @Test
        @DisplayName("a failed sign-in never echoes the password back")
        void failureDoesNotEchoTheCredential() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);

            ResponseEntity<String> response = login(warden.username(), PASSWORD);
            ResponseEntity<String> failure = login(warden.username(), "hunter2");

            // Neither the submitted password nor the stored hash may appear in any
            // response. The original Django app returned str(e) to the client, which is
            // exactly how a credential ends up in a browser's network log.
            assertThat(failure.getBody()).doesNotContain("hunter2");
            assertThat(response.getBody()).doesNotContain(PASSWORD).doesNotContain("$2a$");
        }

        @Test
        @DisplayName("a disabled account cannot sign in at all")
        void disabledAccountCannotSignIn() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            disableUser(warden.userId());

            ResponseEntity<String> response = login(warden.username(), PASSWORD);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a missing password is a validation failure, not an authentication attempt")
        void blankCredentialsAreRejectedBeforeHashing() {
            ResponseEntity<String> response = rest.exchange(LOGIN, HttpMethod.POST,
                    new HttpEntity<>("{\"username\":\"\",\"password\":\"\"}", jsonHeaders()), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Nested
    @DisplayName("the access token it issues")
    class AccessToken {

        @Test
        @DisplayName("works on a protected endpoint straight away")
        void tokenIsUsableImmediately() throws Exception {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String accessToken = accessTokenIn(login(warden.username(), PASSWORD));

            ResponseEntity<String> me = rest.exchange(ME, HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(accessToken)), String.class);

            assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json.readTree(me.getBody()).path("username").asText()).isEqualTo(warden.username());
        }

        @Test
        @DisplayName("carries the scope from the database, not from anything the client sent")
        void scopeComesFromTheAccount() throws Exception {
            SeededUser mensWarden = seedWardenWithPassword(HostelScope.MH, PASSWORD);

            ResponseEntity<String> response = login(mensWarden.username(), PASSWORD);

            assertThat(json.readTree(response.getBody()).path("user").path("hostelScope").asText())
                    .isEqualTo("MH");
        }
    }

    @Nested
    @DisplayName("refreshing")
    class Refreshing {

        @Test
        @DisplayName("exchanges the cookie for a new access token and a new cookie")
        void refreshRotatesTheToken() throws Exception {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            ResponseEntity<String> signIn = login(warden.username(), PASSWORD);
            String first = refreshTokenIn(signIn);

            ResponseEntity<String> refreshed = refresh(first);

            assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json.readTree(refreshed.getBody()).path("accessToken").asText()).isNotBlank();

            String second = refreshTokenIn(refreshed);
            // A new opaque value every time. Reusing the same string and only extending
            // its expiry would mean one stolen cookie is valid for as long as the user
            // keeps the session alive.
            assertThat(second).isNotBlank().isNotEqualTo(first);
        }

        @Test
        @DisplayName("the rotated-away token is dead on arrival")
        void spentTokenIsRejected() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String first = refreshTokenIn(login(warden.username(), PASSWORD));
            refresh(first);

            ResponseEntity<String> replay = refresh(first);

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(replay)).isEqualTo("REFRESH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("replaying a spent token revokes the whole family, live successor included")
        void reuseRevokesEveryTokenInTheFamily() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String first = refreshTokenIn(login(warden.username(), PASSWORD));
            String second = refreshTokenIn(refresh(first));

            // The attacker's replay of the stolen, already-spent token.
            assertThat(refresh(first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // The legitimate user's live token is now dead as well. That is the point:
            // two parties hold tokens from one chain, the server cannot tell which is
            // the thief, so it ends the session and forces a password sign-in. Leaving
            // the successor alive would let the thief keep rotating forever, invisibly.
            ResponseEntity<String> legitimate = refresh(second);
            assertThat(legitimate.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(legitimate)).isEqualTo("REFRESH_TOKEN_INVALID");

            assertThat(liveRefreshTokenCount(warden.userId()))
                    .as("no usable session survives a detected replay")
                    .isZero();
        }

        @Test
        @DisplayName("rotation links each token to its successor, so the chain is auditable")
        void rotationRecordsTheSuccessor() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String first = refreshTokenIn(login(warden.username(), PASSWORD));
            refresh(first);

            Long replacedBy = jdbc.queryForObject(
                    "SELECT replaced_by_id FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NOT NULL",
                    Long.class, warden.userId());

            // Without the link, "this token was revoked" and "this token was rotated
            // normally" look identical in the table, and reuse detection has nothing to
            // report beyond the fact that something went wrong somewhere.
            assertThat(replacedBy).isNotNull();
        }

        @Test
        @DisplayName("no cookie at all is a 401, not a 500")
        void missingCookieIsUnauthorised() {
            ResponseEntity<String> response = rest.exchange(REFRESH, HttpMethod.POST,
                    new HttpEntity<>(jsonHeaders()), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(response)).isEqualTo("REFRESH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("a fabricated cookie value is rejected without leaking whether it looked plausible")
        void unknownTokenIsUnauthorised() {
            ResponseEntity<String> response = refresh("a".repeat(43));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(response)).isEqualTo("REFRESH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("an account disabled after sign-in loses its session at the next refresh")
        void disablingAnAccountStopsItAtTheRefreshBoundary() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String refreshToken = refreshTokenIn(login(warden.username(), PASSWORD));

            disableUser(warden.userId());

            ResponseEntity<String> response = refresh(refreshToken);

            // This is the deliberate tradeoff of stateless access tokens: the already
            // issued one keeps working until it expires, at most the access-token TTL.
            // The refresh boundary is where revocation actually lands, which is why the
            // TTL is 15 minutes and not a day.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(response)).isEqualTo("REFRESH_TOKEN_INVALID");
            assertThat(liveRefreshTokenCount(warden.userId())).isZero();
        }

        @Test
        @DisplayName("a role change takes effect at the next refresh without a sign-out")
        void refreshRebuildsThePrincipalFromTheDatabase() throws Exception {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String refreshToken = refreshTokenIn(login(warden.username(), PASSWORD));

            jdbc.update("UPDATE users SET hostel_scope = 'MH' WHERE id = ?", warden.userId());

            ResponseEntity<String> refreshed = refresh(refreshToken);

            // The claims are rebuilt from the row rather than copied out of the old
            // token, so an administrative change to scope propagates within one access
            // token's lifetime instead of persisting for the refresh token's 30 days.
            assertThat(json.readTree(refreshed.getBody()).path("user").path("hostelScope").asText())
                    .isEqualTo("MH");
        }
    }

    @Nested
    @DisplayName("signing out")
    class SigningOut {

        @Test
        @DisplayName("revokes the session and clears the cookie")
        void logoutRevokes() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String refreshToken = refreshTokenIn(login(warden.username(), PASSWORD));

            ResponseEntity<String> logout = logout(refreshToken);

            assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            // Max-Age=0 so the browser drops it. Revoking server-side but leaving the
            // cookie in place would send a dead credential on every later request.
            assertThat(setCookieHeaderIn(logout)).contains("Max-Age=0");
            assertThat(refresh(refreshToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(liveRefreshTokenCount(warden.userId())).isZero();
        }

        @Test
        @DisplayName("signing out twice is not an error")
        void logoutIsIdempotent() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String refreshToken = refreshTokenIn(login(warden.username(), PASSWORD));

            assertThat(logout(refreshToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            // A second click, a retried request, or a browser replaying the tab-close
            // beacon must not produce an error page. Sign-out has one outcome: signed out.
            assertThat(logout(refreshToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("signing out with no cookie at all still succeeds")
        void logoutWithoutACookieSucceeds() {
            ResponseEntity<String> response = rest.exchange(LOGOUT, HttpMethod.POST,
                    new HttpEntity<>(jsonHeaders()), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("one device signing out leaves the other device signed in")
        void logoutIsPerSession() {
            SeededUser warden = seedWardenWithPassword(HostelScope.LH, PASSWORD);
            String laptop = refreshTokenIn(login(warden.username(), PASSWORD));
            String phone = refreshTokenIn(login(warden.username(), PASSWORD));

            logout(laptop);

            // Each sign-in gets its own row, so revocation is per session. Revoking the
            // whole user here would be indistinguishable from the reuse-detection path,
            // and closing a laptop lid would sign the phone out too.
            assertThat(refresh(phone).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ---- helpers ----

    private ResponseEntity<String> login(String username, String password) {
        String body = json.createObjectNode()
                .put("username", username)
                .put("password", password)
                .toString();
        return rest.exchange(LOGIN, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private ResponseEntity<String> refresh(String refreshToken) {
        return rest.exchange(REFRESH, HttpMethod.POST,
                new HttpEntity<>(headersWithRefreshCookie(refreshToken)), String.class);
    }

    private ResponseEntity<String> logout(String refreshToken) {
        return rest.exchange(LOGOUT, HttpMethod.POST,
                new HttpEntity<>(headersWithRefreshCookie(refreshToken)), String.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private HttpHeaders headersWithRefreshCookie(String refreshToken) {
        HttpHeaders headers = jsonHeaders();
        headers.add(HttpHeaders.COOKIE, jwtProperties.refreshCookie().name() + "=" + refreshToken);
        return headers;
    }

    private String accessTokenIn(ResponseEntity<String> response) throws Exception {
        return json.readTree(response.getBody()).path("accessToken").asText();
    }

    private String setCookieHeaderIn(ResponseEntity<String> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("no Set-Cookie header on the response").isNotNull().isNotEmpty();
        String name = jwtProperties.refreshCookie().name();
        return cookies.stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " cookie in " + cookies));
    }

    /**
     * Pulls the opaque token out of the {@code Set-Cookie} header, which is the only
     * place it is ever published. Parsed by hand rather than through a cookie store
     * so that each test controls exactly which token it presents -- a store would
     * quietly overwrite the previous value on rotation and make the replay tests
     * impossible to express.
     */
    private String refreshTokenIn(ResponseEntity<String> response) {
        String cookie = setCookieHeaderIn(response);
        String value = cookie.substring(cookie.indexOf('=') + 1);
        int end = value.indexOf(';');
        return end < 0 ? value : value.substring(0, end);
    }

    private int liveRefreshTokenCount(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens "
                        + "WHERE user_id = ? AND revoked_at IS NULL AND expires_at > now()",
                Integer.class, userId);
        return count == null ? 0 : count;
    }
}
