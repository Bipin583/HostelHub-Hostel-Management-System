package com.hostelops.exception;

import org.springframework.http.HttpStatus;

/**
 * Every error this API can return, with the status it maps to.
 *
 * <p>The code is part of the public contract: the typed frontend client
 * switches on it, so codes are stable and renaming one is a breaking change.
 * The HTTP status lives here rather than at the throw site so a given failure
 * cannot come back as a 400 from one endpoint and a 409 from another.
 */
public enum ErrorCode {

    // 400
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    /** The register cannot be marked for a date that has not happened yet. */
    ATTENDANCE_DATE_INVALID(HttpStatus.BAD_REQUEST),
    /** Resolving a complaint requires saying what was done about it. */
    RESOLUTION_NOTE_REQUIRED(HttpStatus.BAD_REQUEST),
    /** A payment for more than the invoice still owes, or for nothing at all. */
    PAYMENT_AMOUNT_INVALID(HttpStatus.BAD_REQUEST),
    /**
     * A gateway callback whose signature does not match. A 400 rather than a 403: the
     * caller is not an authenticated principal being denied, it is an unauthentic
     * message. Nothing about it is worth distinguishing for whoever sent it.
     */
    PAYMENT_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST),

    // 401
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),

    // 403
    FORBIDDEN(HttpStatus.FORBIDDEN),
    /** The caller's role is allowed here, but this row is outside their hostel scope. */
    OUT_OF_SCOPE(HttpStatus.FORBIDDEN),

    // 404
    NOT_FOUND(HttpStatus.NOT_FOUND),

    // 405
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

    // 409
    CONFLICT(HttpStatus.CONFLICT),
    /** The room had no free bed by the time the allocation was committed. */
    ROOM_FULL(HttpStatus.CONFLICT),
    STUDENT_ALREADY_ALLOCATED(HttpStatus.CONFLICT),
    STUDENT_NOT_ALLOCATED(HttpStatus.CONFLICT),
    ROOM_NOT_ELIGIBLE(HttpStatus.CONFLICT),
    DUPLICATE_APPLICATION(HttpStatus.CONFLICT),
    NO_ROOM_AVAILABLE(HttpStatus.CONFLICT),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    /** The invoice is already paid in full, or was cancelled. */
    FEE_ALREADY_SETTLED(HttpStatus.CONFLICT),
    /** The payment attempt has already succeeded or failed; a gateway resent its callback. */
    PAYMENT_ALREADY_SETTLED(HttpStatus.CONFLICT),
    ALERT_ALREADY_ACKNOWLEDGED(HttpStatus.CONFLICT),

    // 429
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    // 500
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR),

    /**
     * The payment gateway could not be reached, or answered with something we do not
     * understand.
     *
     * <p>A 502 rather than a 500 because the distinction is the one the caller needs:
     * nothing is wrong with the request and nothing is wrong here, so retrying the
     * identical request -- with the same {@code Idempotency-Key} -- is the correct
     * response. A 500 would tell them to stop.
     */
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
