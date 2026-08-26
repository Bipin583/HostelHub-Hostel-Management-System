/**
 * The bottom of the fetch stack: base URL, response decoding, error translation.
 *
 * <p>This module exists so that `session.ts` and `api.ts` can share response
 * handling without importing each other. `api.ts` needs the session to attach a
 * Bearer token and to refresh on a 401; the session needs to call
 * `/api/v1/auth/*` itself. If both lived in one file the refresh path would be
 * reentrant with the very interceptor that triggered it. Splitting the *decoding*
 * out here breaks the cycle without duplicating the envelope logic in two places.
 */

import { ApiError } from './api-error';

/** Matches the backend's own default port, for the dev fallback below. */
const DEV_FALLBACK_BASE_URL = 'http://localhost:8080';

/**
 * Where Spring is.
 *
 * <p>Resolved on first use rather than at module load, and that is not fussiness.
 * `next build` evaluates every client module while prerendering, so a module-level
 * `throw` on a missing variable would turn a misconfigured environment into a build
 * failure with a stack trace pointing at a URL helper. Deferring means the same
 * mistake surfaces at the point of the first request, where the UI already knows
 * how to show an error.
 *
 * <p>In development a missing variable falls back to the port the backend's
 * `application.yml` actually uses, so `npm run dev` works from a fresh clone. In a
 * production bundle it throws instead: silently pointing a deployed app at
 * localhost fails for every user in a way that looks like a backend outage.
 */
let resolvedBaseUrl: string | null = null;

export function apiBaseUrl(): string {
  if (resolvedBaseUrl !== null) return resolvedBaseUrl;
  const configured = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (configured) {
    resolvedBaseUrl = configured.replace(/\/+$/, '');
  } else if (process.env.NODE_ENV === 'production') {
    throw new Error(
      'NEXT_PUBLIC_API_BASE_URL is not set. Copy .env.example to .env.local and point it at the backend.',
    );
  } else {
    resolvedBaseUrl = DEV_FALLBACK_BASE_URL;
  }
  return resolvedBaseUrl;
}

/** Absolute URL for an API path, with query parameters appended when given. */
export function apiUrl(path: string, query?: QueryParams): string {
  const base = `${apiBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`;
  const search = toSearchParams(query);
  return search ? `${base}?${search}` : base;
}

/**
 * Values a caller may pass as query parameters.
 *
 * `undefined` and `null` are dropped rather than sent as the strings "undefined"
 * and "null". This matters because most list endpoints take optional filters --
 * `status`, `block`, `query` -- and Spring treats a present-but-empty parameter
 * differently from an absent one (an empty `status` is a 400, not "all statuses").
 * So the natural `{ status: selected }` where `selected` may be unset does the
 * right thing without every call site writing a conditional.
 */
export type QueryParams = Record<
  string,
  string | number | boolean | null | undefined | readonly (string | number)[]
>;

export function toSearchParams(query?: QueryParams): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      for (const item of value) params.append(key, String(item));
    } else {
      params.append(key, String(value));
    }
  }
  return params.toString();
}

/**
 * Decode a successful response, or throw the server's error as an `ApiError`.
 *
 * <p>Three shapes have to be handled and each has a reason:
 *
 * <ul>
 *   <li><b>204</b> -- `DELETE /allocations/student/{id}` and `POST /auth/logout`
 *       return no body. `response.json()` on an empty body throws a parse error
 *       that would surface as "Unexpected end of JSON input", so 204 short-circuits
 *       to `undefined` before anything tries to read it.</li>
 *   <li><b>the error envelope</b> -- every handled failure. The nested `error`
 *       object is unwrapped so callers see `e.code`, not `e.error.code`.</li>
 *   <li><b>an unenveloped failure</b> -- a 502 from a proxy, or Spring's own
 *       container-level 401 before the handler runs. There is no envelope to read,
 *       so the status line becomes the message rather than letting a JSON parse
 *       failure mask the real status.</li>
 * </ul>
 */
export async function decode<T>(response: Response): Promise<T> {
  if (response.ok) {
    if (response.status === 204) return undefined as T;
    const text = await response.text();
    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }
  throw await toApiError(response);
}

/** Build an `ApiError` from a non-2xx response, whatever it happens to contain. */
export async function toApiError(response: Response): Promise<ApiError> {
  // Sent by RateLimitingFilter and exposed to the browser through
  // CorsConfiguration.exposedHeaders -- without that it would be invisible here.
  const retryAfter = Number(response.headers.get('Retry-After'));
  const retryAfterSeconds = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : null;

  let payload: unknown = null;
  try {
    const text = await response.text();
    payload = text ? JSON.parse(text) : null;
  } catch {
    payload = null; // Not JSON. Fall through to the status-line message.
  }

  const envelope = extractEnvelope(payload);
  return new ApiError({
    status: response.status,
    code: envelope?.code ?? `HTTP_${response.status}`,
    message: envelope?.message ?? `${response.status} ${response.statusText || 'Request failed'}`.trim(),
    details: envelope?.details ?? null,
    traceId: envelope?.traceId ?? null,
    retryAfterSeconds,
  });
}

interface Envelope {
  code: string;
  message: string;
  details: unknown;
  traceId: string | null;
}

function extractEnvelope(payload: unknown): Envelope | null {
  if (payload === null || typeof payload !== 'object') return null;
  const error = (payload as { error?: unknown }).error;
  if (error === null || typeof error !== 'object') return null;
  const record = error as Record<string, unknown>;
  const code = typeof record.code === 'string' ? record.code : null;
  const message = typeof record.message === 'string' ? record.message : null;
  if (!code && !message) return null;
  return {
    code: code ?? 'INTERNAL',
    message: message ?? 'The server rejected the request.',
    details: record.details ?? null,
    traceId: typeof record.traceId === 'string' ? record.traceId : null,
  };
}

/** Headers for a JSON request body. */
export const JSON_HEADERS: Readonly<Record<string, string>> = { 'Content-Type': 'application/json' };
