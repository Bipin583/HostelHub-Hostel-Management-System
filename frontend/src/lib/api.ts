/**
 * The authenticated fetch layer. Every call to a `/api/v1/**` data endpoint goes
 * through `request`.
 *
 * <p>One function owns four concerns that are otherwise copy-pasted into every call
 * site and get subtly wrong in one of them: the bearer header, the error envelope,
 * the 401 recovery, and the `Idempotency-Key` on payment initiation.
 *
 * <h2>Two refresh paths, not one</h2>
 *
 * <p>There is a proactive path and a reactive one, and both are needed.
 *
 * <ul>
 *   <li><b>Proactive</b>: if the token is inside its renewal margin, refresh
 *       *before* sending. This keeps the common case off the failure path -- no
 *       request is spent discovering that the clock moved.</li>
 *   <li><b>Reactive</b>: a 401 still triggers one refresh and one replay. A laptop
 *       that was suspended for an hour wakes with a long-dead token and a timer
 *       that never fired; the server's rejection is the only reliable signal. Also
 *       the token can be revoked server-side at any moment, and nothing local
 *       knows that until a request fails.</li>
 * </ul>
 *
 * <p>The replay happens exactly once. A second 401 after a successful refresh is
 * not a stale token -- it is a token the server refuses to accept -- and retrying
 * that in a loop turns one rejection into a hammering of the login rate limiter.
 *
 * <p>Both paths call `session.refresh()`, which is single-flight, so a page that
 * fires ten requests into an expired token produces one refresh and ten replays,
 * not ten refreshes that revoke each other. That property lives in session.ts
 * because it has to be true for the proactive path too.
 */

import { ApiError } from './api-error';
import { apiUrl, decode, type QueryParams } from './http';
import { getAccessToken, isAccessTokenStale, refresh } from './session';
import type { Page } from './types';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface RequestOptions {
  query?: QueryParams;
  /** Serialised as JSON. Omit for a body-less POST -- several endpoints have one. */
  body?: unknown;
  /**
   * Value for the `Idempotency-Key` header.
   *
   * Required by `POST /api/v1/student/payments` and meaningless everywhere else.
   * It is a first-class option rather than a raw header so that call sites read as
   * "this operation is idempotent under this key", and so the one endpoint that
   * needs it cannot forget it silently -- see `initiatePayment`.
   */
  idempotencyKey?: string;
  signal?: AbortSignal;
}

/**
 * Send one request, recovering from a single expired-token 401.
 *
 * @throws ApiError for any non-2xx response, after the retry has been exhausted
 */
export async function request<T>(method: HttpMethod, path: string, options: RequestOptions = {}): Promise<T> {
  // Proactive renewal. A failure is swallowed: if the refresh cookie is gone the
  // session is already cleared, and the request below will get a clean 401 that
  // the guards translate into a redirect. Throwing here instead would report a
  // login problem as a failure of whatever page the user was looking at.
  if (isAccessTokenStale()) {
    await refresh().catch(() => undefined);
  }

  const attempt = (): Promise<Response> => fetch(apiUrl(path, options.query), buildInit(method, options));

  let response = await attempt();
  if (response.status === 401 && getAccessToken() !== null) {
    try {
      await refresh();
    } catch {
      // The refresh failed, so the original 401 is the truthful error. Fall through
      // and let it be decoded, rather than replacing the server's message with one
      // about refreshing.
      return decode<T>(response);
    }
    response = await attempt(); // Exactly one replay. See the class note.
  }
  return decode<T>(response);
}

function buildInit(method: HttpMethod, options: RequestOptions): RequestInit {
  const headers = new Headers();
  const token = getAccessToken();
  if (token !== null) headers.set('Authorization', `Bearer ${token}`);
  if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey);
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');

  return {
    method,
    headers,
    // Not 'include'. Authorisation on data endpoints is the bearer header, and the
    // refresh cookie is scoped to path /api/v1/auth, so the browser would not
    // attach it here anyway. Saying 'omit' makes that deliberate rather than
    // incidental: nothing outside session.ts depends on cookies.
    credentials: 'omit',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
    // The backend is the source of truth for every one of these reads, and several
    // are counters that change under the user (open alerts, outstanding fees). A
    // cached 200 would show yesterday's numbers with no way to tell.
    cache: 'no-store',
  };
}

// ------------------------------------------------------------------ shorthands

export const get = <T,>(path: string, query?: QueryParams, signal?: AbortSignal): Promise<T> =>
  request<T>('GET', path, { query, signal });

export const post = <T,>(path: string, options: RequestOptions = {}): Promise<T> =>
  request<T>('POST', path, options);

export const put = <T,>(path: string, options: RequestOptions = {}): Promise<T> =>
  request<T>('PUT', path, options);

export const del = <T = void,>(path: string, options: RequestOptions = {}): Promise<T> =>
  request<T>('DELETE', path, options);

/**
 * A paged GET, with Spring Data's parameter names.
 *
 * <p>`page` is zero-based on the server, and the UI's page numbers are too, so no
 * translation happens anywhere -- an off-by-one in pagination is invisible until
 * someone notices a missing row. `sort` is passed as `field,DIR`, the format
 * `Pageable` parses; each controller already declares a sensible `@PageableDefault`,
 * so omitting it is the normal case.
 */
export function getPage<T>(
  path: string,
  params: { page?: number; size?: number; sort?: string } & QueryParams = {},
  signal?: AbortSignal,
): Promise<Page<T>> {
  return get<Page<T>>(path, params, signal);
}

/** True when this error means "sign in again" rather than "that request failed". */
export function isUnauthenticated(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
