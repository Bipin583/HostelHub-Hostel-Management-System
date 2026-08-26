/**
 * The server's `ErrorCode` enum, mirrored so the UI can branch on it.
 *
 * <p>Kept in its own module rather than inside types.ts because it is imported by
 * the fetch layer, which is the one part of this app that must not pull in the
 * whole domain vocabulary just to name an error.
 *
 * The union is deliberately not the type of `ApiError.code`. A server that adds a
 * code should not turn into a crash here, so the field is `ErrorCode | string` and
 * this union exists to make the codes the UI *handles* checkable -- a typo in
 * `codeOf(e) === 'ROOM_FULLL'` is caught, an unknown code from the future is not
 * an error.
 */
export type ErrorCode =
  // 400 -- the request was wrong
  | 'VALIDATION_FAILED'
  | 'MALFORMED_REQUEST'
  | 'BAD_REQUEST'
  | 'ATTENDANCE_DATE_INVALID'
  | 'RESOLUTION_NOTE_REQUIRED'
  | 'PAYMENT_AMOUNT_INVALID'
  | 'PAYMENT_VERIFICATION_FAILED'
  // 401 -- who are you
  | 'UNAUTHENTICATED'
  | 'INVALID_CREDENTIALS'
  | 'TOKEN_INVALID'
  | 'REFRESH_TOKEN_INVALID'
  // 403 -- known, but not allowed
  | 'FORBIDDEN'
  | 'OUT_OF_SCOPE'
  // 404 / 405
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  // 409 -- the state of the world says no
  | 'CONFLICT'
  | 'ROOM_FULL'
  | 'STUDENT_ALREADY_ALLOCATED'
  | 'STUDENT_NOT_ALLOCATED'
  | 'ROOM_NOT_ELIGIBLE'
  | 'DUPLICATE_APPLICATION'
  | 'NO_ROOM_AVAILABLE'
  | 'ILLEGAL_STATE_TRANSITION'
  | 'DUPLICATE_RESOURCE'
  | 'FEE_ALREADY_SETTLED'
  | 'PAYMENT_ALREADY_SETTLED'
  | 'ALERT_ALREADY_ACKNOWLEDGED'
  // 429
  | 'RATE_LIMITED'
  // 5xx
  | 'INTERNAL'
  | 'PAYMENT_GATEWAY_ERROR';
