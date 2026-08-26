/**
 * The one error type this app throws for anything that came back from Spring.
 *
 * <p>The backend never returns a bare string on a failure. Every non-2xx response
 * from `GlobalExceptionHandler` is the same envelope:
 *
 * <pre>
 * { "error": { "code": "ROOM_FULL", "message": "...", "details": {...},
 *              "traceId": "...", "timestamp": "..." } }
 * </pre>
 *
 * That is a contract worth leaning on. Because `code` is a closed enum on the
 * server, the UI can branch on a stable machine value instead of pattern-matching
 * English prose -- so `ROOM_FULL` can put the message next to the room picker while
 * `RATE_LIMITED` can show a countdown, and neither breaks when somebody rewords a
 * message. `message` is still shown verbatim, because the server writes better
 * copy about its own state than a switch statement here could.
 *
 * `details` carries per-field validation failures for VALIDATION_FAILED. Forms read
 * it through `fieldErrors()` so a 400 lands on the offending input rather than in a
 * banner at the top of the page.
 */

import type { ErrorCode } from './error-codes';

/** Field-level messages from a `VALIDATION_FAILED` response. */
export type FieldErrors = Record<string, string>;

export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode | string;
  readonly details: unknown;
  readonly traceId: string | null;
  /** Seconds from the `Retry-After` header. Only ever set on a 429. */
  readonly retryAfterSeconds: number | null;

  constructor(init: {
    status: number;
    code: ErrorCode | string;
    message: string;
    details?: unknown;
    traceId?: string | null;
    retryAfterSeconds?: number | null;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.details = init.details ?? null;
    this.traceId = init.traceId ?? null;
    this.retryAfterSeconds = init.retryAfterSeconds ?? null;
  }

  /**
   * Per-field messages, or an empty object when this wasn't a validation failure.
   *
   * The server sends `details` as a flat map of field name to message. Anything
   * that isn't that shape is ignored rather than guessed at: a form that renders
   * nothing is recoverable, a form that renders `[object Object]` is not.
   */
  fieldErrors(): FieldErrors {
    if (this.details === null || typeof this.details !== 'object' || Array.isArray(this.details)) {
      return {};
    }
    const out: FieldErrors = {};
    for (const [field, message] of Object.entries(this.details as Record<string, unknown>)) {
      if (typeof message === 'string') out[field] = message;
    }
    return out;
  }

  /** True when the caller's own credentials are the problem, not the request. */
  isAuthFailure(): boolean {
    return this.status === 401;
  }
}

/**
 * Turn any thrown value into a message safe to render.
 *
 * A rejected `fetch` is a `TypeError` with a browser-authored message like
 * "Failed to fetch", which tells a user nothing. Since the only way this app's
 * fetches fail at the transport layer is an unreachable backend or a CORS
 * rejection, that case gets its own sentence naming the likely cause -- during
 * development it is nearly always one of those two.
 */
export function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof TypeError) {
    return 'Could not reach the API. Check that the backend is running and that this origin is in its CORS allow-list.';
  }
  if (error instanceof Error && error.message) return error.message;
  return 'Something went wrong.';
}

/** The server's error code, when the failure came from the server at all. */
export function codeOf(error: unknown): string | null {
  return error instanceof ApiError ? String(error.code) : null;
}
