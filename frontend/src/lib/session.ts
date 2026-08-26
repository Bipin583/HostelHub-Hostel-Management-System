/**
 * Who is signed in, and the only code that talks to `/api/v1/auth/*`.
 *
 * <p>This is the file the architecture note in `next.config.mjs` points at, so the
 * whole argument lives here.
 *
 * <h2>Why the browser calls Spring directly</h2>
 *
 * <p>The obvious Next.js shape is a BFF: the browser talks to route handlers on
 * this server, which hold the session and forward to Spring. This app deliberately
 * does not do that, because the backend was built for the other shape and the
 * evidence is in its own configuration:
 *
 * <ul>
 *   <li>`SecurityConfig` registers a `CorsConfigurationSource` on `/api/**` with
 *       enumerated browser origins, `allowCredentials(true)`, and
 *       `Idempotency-Key` in `allowedHeaders`. A server-to-server proxy needs none
 *       of that -- CORS is a browser mechanism. Those lines only make sense if a
 *       browser is the caller.</li>
 *   <li>The refresh cookie is `HttpOnly`, `SameSite=Strict`, and scoped to path
 *       `/api/v1/auth`. That is a cookie designed to be held by a browser and sent
 *       to exactly two endpoints. Behind a proxy the cookie's SameSite protection
 *       would be doing nothing, because the browser's request would go to Next, not
 *       to Spring.</li>
 *   <li>`Retry-After` is in `exposedHeaders`. A header is only "exposed" to
 *       JavaScript running in a browser under CORS.</li>
 * </ul>
 *
 * <p>A proxy would also have to put the access token somewhere on this server, and
 * there is deliberately no server-side session here to put it in -- adding one
 * would mean a second store of credentials, a second expiry policy, and a second
 * thing that can be wrong, in exchange for nothing the backend asked for.
 *
 * <h2>Where the tokens live</h2>
 *
 * <p>The access token is a module variable. Not `localStorage`, not
 * `sessionStorage`, not a readable cookie: anything persistent and script-readable
 * is exactly what an XSS payload exfiltrates, and a 15-minute token in memory dies
 * with the tab. The cost is that a page reload loses it -- which is what the
 * refresh cookie is for. `restore()` spends one request on startup to trade the
 * cookie for a new access token, and that request is the only reason a reload feels
 * like a session rather than a logout.
 *
 * <h2>Why refresh is single-flight</h2>
 *
 * <p>`AuthService.refresh` rotates: it revokes the presented refresh token and
 * issues a new one. That makes the token single-use, which makes concurrency a
 * correctness problem rather than an efficiency one. A dashboard that fires six
 * requests at once and gets six 401s would, without care, send six refreshes; the
 * first would succeed and rotate the cookie, and the other five would present a
 * token the server has already revoked -- and a revoked refresh token is not a
 * retryable error, it is a logout. So every caller that needs a refresh awaits the
 * *same* promise. See `refresh()`.
 */

import { ApiError } from './api-error';
import { apiUrl, decode, JSON_HEADERS } from './http';
import type { AuthResponse, Role, UserSummary } from './types';

/** Backend paths owned by this module. Nothing else may call them. */
const LOGIN = '/api/v1/auth/login';
const REFRESH = '/api/v1/auth/refresh';
const LOGOUT = '/api/v1/auth/logout';

/**
 * How long before expiry to renew, in seconds.
 *
 * <p>A 15-minute access token renewed 60 seconds early costs one extra request per
 * quarter hour and removes the whole class of "the request that happened to cross
 * the expiry boundary". The reactive 401 path in `api.ts` still exists, because a
 * laptop that was asleep will blow through any timer.
 */
const RENEW_MARGIN_SECONDS = 60;

/**
 * `unknown` is the state before `restore()` has answered.
 *
 * <p>It is a distinct state from `anonymous` on purpose. A guard that cannot tell
 * "not signed in" from "we have not asked yet" will bounce every signed-in user to
 * the login page for the one frame before the refresh returns -- and with the App
 * Router that bounce is a real navigation, not a flicker.
 */
export type SessionStatus = 'unknown' | 'authenticated' | 'anonymous';

export interface SessionSnapshot {
  status: SessionStatus;
  user: UserSummary | null;
}

const ANONYMOUS: SessionSnapshot = { status: 'anonymous', user: null };
const UNKNOWN: SessionSnapshot = { status: 'unknown', user: null };

// ------------------------------------------------------------------- the state

/**
 * The access token, and the two things derived from it, kept apart from the
 * snapshot components subscribe to.
 *
 * <p>The token is *not* in `SessionSnapshot`. If it were, every proactive refresh
 * would publish a new snapshot and re-render every subscribed component every
 * fifteen minutes for a value none of them reads. Components need to know *who* is
 * signed in; only `api.ts` needs the bearer string, and it asks for it directly.
 */
let accessToken: string | null = null;
let expiresAtMillis: number | null = null;
let renewTimer: ReturnType<typeof setTimeout> | null = null;

let snapshot: SessionSnapshot = UNKNOWN;
const listeners = new Set<() => void>();

/** In-flight refresh, shared by every caller. Null when none is running. */
let refreshInFlight: Promise<AuthResponse> | null = null;

/** Whether `restore()` has already been started, so a remount doesn't repeat it. */
let restoreStarted = false;

// ------------------------------------------------------- the observable store

/**
 * Subscribe to identity changes. Shaped for `useSyncExternalStore`.
 *
 * @returns an unsubscribe function
 */
export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * The current snapshot.
 *
 * <p>Returns the same object until something actually changes. `useSyncExternalStore`
 * compares snapshots by identity and will throw about an infinite loop if this
 * builds a fresh object per call, so the object is replaced only in `publish()`.
 */
export function getSnapshot(): SessionSnapshot {
  return snapshot;
}

/**
 * The snapshot the server render sees.
 *
 * <p>Always `unknown`, and it has to be: the server has no access to the browser's
 * cookie jar in a client component's prerender, so claiming `anonymous` would make
 * the server's HTML disagree with the client's first paint for every signed-in
 * user, which is a hydration mismatch.
 */
export function getServerSnapshot(): SessionSnapshot {
  return UNKNOWN;
}

function publish(next: SessionSnapshot): void {
  snapshot = next;
  for (const listener of listeners) listener();
}

// --------------------------------------------------------------- token access

/** The bearer token for the next request, or null when there is none. */
export function getAccessToken(): string | null {
  return accessToken;
}

/** True when the token exists but is inside its renewal margin (or already past). */
export function isAccessTokenStale(): boolean {
  if (accessToken === null || expiresAtMillis === null) return false;
  return Date.now() >= expiresAtMillis - RENEW_MARGIN_SECONDS * 1000;
}

export function currentUser(): UserSummary | null {
  return snapshot.user;
}

/** Convenience for the role guards; null when nobody is signed in. */
export function currentRole(): Role | null {
  return snapshot.user?.role ?? null;
}

// ---------------------------------------------------------------- the actions

/**
 * Exchange credentials for a session.
 *
 * <p>`credentials: 'include'` is what lets the browser store the `Set-Cookie` the
 * backend returns. Without it the login appears to succeed and then every reload
 * logs the user out, because the refresh cookie was silently dropped.
 *
 * <p>Errors are not caught: the login form needs to tell `INVALID_CREDENTIALS`
 * apart from `RATE_LIMITED` (which carries a `Retry-After` this app shows as a
 * countdown), and swallowing the `ApiError` here would erase that difference.
 */
export async function login(username: string, password: string): Promise<UserSummary> {
  const response = await fetch(apiUrl(LOGIN), {
    method: 'POST',
    headers: JSON_HEADERS,
    credentials: 'include',
    body: JSON.stringify({ username, password }),
  });
  const auth = await decode<AuthResponse>(response);
  adopt(auth);
  return auth.user;
}

/**
 * Trade the refresh cookie for a new access token -- at most once at a time.
 *
 * <p>The single-flight is the whole point (see the class note). The promise is
 * stored *before* it is awaited, so a caller arriving mid-flight joins the existing
 * request rather than starting a rival one, and it is cleared in a `finally` so a
 * failed refresh does not poison every later attempt with a rejected promise.
 *
 * <p>A refresh that fails with 401 is terminal, not transient: the cookie is
 * missing, expired, or already rotated away, and no amount of retrying brings it
 * back. That case clears the session so the guards can send the user to login.
 * Anything else -- a 500, a dropped connection -- leaves the session alone,
 * because a backend hiccup is not a logout.
 */
export function refresh(): Promise<AuthResponse> {
  if (refreshInFlight !== null) return refreshInFlight;

  refreshInFlight = (async () => {
    try {
      // No body: RefreshRequest does not exist. The cookie *is* the request, which
      // is why `credentials: 'include'` is not optional here.
      const response = await fetch(apiUrl(REFRESH), { method: 'POST', credentials: 'include' });
      const auth = await decode<AuthResponse>(response);
      adopt(auth);
      return auth;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) clearSession();
      throw error;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

/**
 * Establish the session on a cold page load.
 *
 * <p>Runs once per page lifetime, guarded by `restoreStarted` -- React Strict Mode
 * mounts effects twice in development, and this must not become two refreshes of a
 * single-use token. `refresh()` would in fact collapse them, but relying on that
 * would make a double-mount indistinguishable from a bug.
 *
 * <p>A failure here is expected and normal: it is what a first-time visitor with no
 * cookie looks like. So the rejection is swallowed and the state becomes
 * `anonymous`, which is a fact about the user, not an error to display.
 */
export async function restore(): Promise<void> {
  if (restoreStarted) return;
  restoreStarted = true;
  try {
    await refresh();
  } catch {
    clearSession();
  }
}

/**
 * End the session on the server, then locally.
 *
 * <p>The server call is best-effort. `AuthController.logout` is idempotent and
 * returns 204 even with no cookie, so the only ways it fails are network-level --
 * and a user who clicked "sign out" must end up signed out of this tab regardless.
 * Clearing locally in a `finally` means a flaky network cannot trap someone in a
 * session they asked to leave.
 */
export async function logout(): Promise<void> {
  try {
    await fetch(apiUrl(LOGOUT), { method: 'POST', credentials: 'include' });
  } catch {
    // Deliberately ignored -- see above.
  } finally {
    clearSession();
  }
}

/** Drop all local credentials and announce it. Safe to call when already clear. */
export function clearSession(): void {
  accessToken = null;
  expiresAtMillis = null;
  cancelRenew();
  if (snapshot.status !== 'anonymous') publish(ANONYMOUS);
}

// ---------------------------------------------------------------- internals

/** Take ownership of a fresh `AuthResponse`: store the token, publish the user. */
function adopt(auth: AuthResponse): void {
  accessToken = auth.accessToken;
  expiresAtMillis = Date.now() + auth.expiresInSeconds * 1000;
  scheduleRenew(auth.expiresInSeconds);

  // Publish only when the identity actually changed. A proactive refresh returns
  // the same user every fifteen minutes; re-rendering the tree for that would be
  // pure waste.
  if (snapshot.status !== 'authenticated' || !sameUser(snapshot.user, auth.user)) {
    publish({ status: 'authenticated', user: auth.user });
  }
}

function sameUser(a: UserSummary | null, b: UserSummary | null): boolean {
  if (a === null || b === null) return a === b;
  return a.id === b.id && a.role === b.role && a.hostelScope === b.hostelScope && a.studentId === b.studentId;
}

/**
 * Renew shortly before expiry.
 *
 * <p>Guarded on `window` because this module is imported by client components that
 * Next also renders on the server; a timer started during prerender would never
 * fire and would hold the render open. A failure is ignored here -- if the cookie
 * is gone, `refresh()` has already cleared the session, and the next user action
 * will land on a guard.
 */
function scheduleRenew(expiresInSeconds: number): void {
  cancelRenew();
  if (typeof window === 'undefined') return;
  const delaySeconds = Math.max(expiresInSeconds - RENEW_MARGIN_SECONDS, RENEW_MARGIN_SECONDS / 2);
  renewTimer = setTimeout(() => {
    renewTimer = null;
    void refresh().catch(() => undefined);
  }, delaySeconds * 1000);
}

function cancelRenew(): void {
  if (renewTimer !== null) {
    clearTimeout(renewTimer);
    renewTimer = null;
  }
}
