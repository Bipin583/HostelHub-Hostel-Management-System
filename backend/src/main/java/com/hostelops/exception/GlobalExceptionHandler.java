package com.hostelops.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates everything that can go wrong into the single {@link ApiError}
 * envelope.
 *
 * <p>The rule that matters: only an {@link ApiException} gets to choose its own
 * message. Every other throwable is a bug, and the client is told nothing but
 * {@code INTERNAL} plus a trace id it can quote to support. The predecessor's
 * login endpoint returned {@code str(e)} straight to the caller, which handed
 * out driver errors, table names and query fragments to anyone who could
 * provoke one.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Database constraints we deliberately let bubble up, mapped back to the
     * domain code they represent.
     *
     * <p>This is what keeps defence-in-depth from looking like a crash: when the
     * capacity trigger or a partial unique index rejects a write, the caller
     * still gets a clean 409 with a meaningful code rather than a 500.
     */
    private static Map.Entry<ErrorCode, String> classifyIntegrityViolation(String causeMessage) {
        String text = causeMessage == null ? "" : causeMessage.toLowerCase();
        if (text.contains("uq_allocations_active_student")) {
            return Map.entry(ErrorCode.STUDENT_ALREADY_ALLOCATED,
                    "This student already holds an active room allocation");
        }
        if (text.contains("is at capacity") || text.contains("trg_allocations_capacity")) {
            return Map.entry(ErrorCode.ROOM_FULL, "That room has no free beds left");
        }
        if (text.contains("uq_applications_one_pending")) {
            return Map.entry(ErrorCode.DUPLICATE_APPLICATION,
                    "This student already has a pending application");
        }
        if (text.contains("ck_rooms_gender_matches_hostel")) {
            return Map.entry(ErrorCode.ROOM_NOT_ELIGIBLE,
                    "A room's eligible gender must match its hostel");
        }
        if (text.contains("uq_users_username")) {
            return Map.entry(ErrorCode.DUPLICATE_RESOURCE, "That username is already taken");
        }
        if (text.contains("uq_students_roll_number")) {
            return Map.entry(ErrorCode.DUPLICATE_RESOURCE, "That roll number is already registered");
        }
        if (text.contains("uq_attendance_student_date")) {
            return Map.entry(ErrorCode.DUPLICATE_RESOURCE,
                    "Attendance for this student on this date is already recorded");
        }
        if (text.contains("uq_fee_reminders_per_day")) {
            return Map.entry(ErrorCode.DUPLICATE_RESOURCE, "A reminder for this fee was already sent today");
        }
        if (text.contains("uq_fee_payments_idempotency")) {
            return Map.entry(ErrorCode.DUPLICATE_RESOURCE, "This payment request was already submitted");
        }
        if (text.contains("uq_fee_payments_provider_ref")) {
            return Map.entry(ErrorCode.DUPLICATE_RESOURCE,
                    "This payment has already been recorded");
        }
        if (text.contains("uq_hostel_fees_term")) {
            return Map.entry(ErrorCode.DUPLICATE_RESOURCE,
                    "This student has already been billed for that term");
        }
        return Map.entry(ErrorCode.CONFLICT, "The request conflicts with the current state of the data");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        // Expected, named failures are not stack-trace-worthy.
        log.debug("Handled {}: {}", ex.getCode(), ex.getMessage());
        return respond(ex.getCode(), ex.getMessage(), ex.getDetails(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return respond(ErrorCode.VALIDATION_FAILED, "The request body failed validation",
                Map.of("fields", fields), null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fields.putIfAbsent(String.valueOf(violation.getPropertyPath()), violation.getMessage());
        }
        return respond(ErrorCode.VALIDATION_FAILED, "One or more parameters failed validation",
                Map.of("fields", fields), null);
    }

    /**
     * Constraints on individual handler parameters -- {@code @Min} on a
     * {@code @RequestParam}, {@code @PhoneNumber} on a path variable.
     *
     * <p>Spring 6.1 moved this off the old {@code @Validated} AOP proxy and into the
     * handler adapter, which raises this type instead of
     * {@link ConstraintViolationException}. Without a handler for it the exception
     * is a {@code ResponseStatusException} that falls through to the catch-all below
     * and reports a client's out-of-range query parameter as a 500 -- a validation
     * failure disguised as a server bug, which is the worst of both: the caller
     * cannot fix it and the log fills with noise that is not ours.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            String message = result.getResolvableErrors().isEmpty()
                    ? "is invalid"
                    : result.getResolvableErrors().get(0).getDefaultMessage();
            fields.putIfAbsent(name == null ? "parameter" : name, message);
        }
        return respond(ErrorCode.VALIDATION_FAILED, "One or more parameters failed validation",
                Map.of("fields", fields), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        // The parser message can quote the payload, so it is logged, not returned.
        log.debug("Unreadable request body", ex);
        return respond(ErrorCode.MALFORMED_REQUEST, "The request body could not be parsed as JSON", null, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(ErrorCode.MALFORMED_REQUEST,
                "Parameter '" + ex.getName() + "' has the wrong type",
                Map.of("parameter", ex.getName()), null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        return respond(ErrorCode.VALIDATION_FAILED,
                "Required parameter '" + ex.getParameterName() + "' is missing",
                Map.of("parameter", ex.getParameterName()), null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex) {
        // Deliberately indistinguishable for "no such user" and "wrong password":
        // a different message for each is a username oracle.
        log.debug("Authentication failed: {}", ex.getClass().getSimpleName());
        return respond(ErrorCode.INVALID_CREDENTIALS, "Incorrect username or password", null, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return respond(ErrorCode.FORBIDDEN, "You do not have permission to perform this action", null, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrityViolation(DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        Map.Entry<ErrorCode, String> classified = classifyIntegrityViolation(cause);
        if (classified.getKey() == ErrorCode.CONFLICT) {
            // An unrecognised constraint means a gap in this mapping: log it in
            // full so it can be classified properly, but still answer 409.
            log.warn("Unclassified constraint violation", ex);
        } else {
            log.debug("Constraint violation mapped to {}", classified.getKey());
        }
        return respond(classified.getKey(), classified.getValue(), null, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return respond(ErrorCode.NOT_FOUND, "No such endpoint", null, null);
    }

    /**
     * Lock contention that the database resolved by aborting us -- a deadlock
     * victim, or a lock wait that timed out.
     *
     * <p>Distinct from {@code ROOM_FULL}: nothing about the request was wrong, it
     * simply lost a race and is safe to send again. {@code CannotAcquireLockException}
     * and {@code DeadlockLoserDataAccessException} both land here, being subtypes.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleLockFailure(PessimisticLockingFailureException ex) {
        log.warn("Lock contention aborted a transaction: {}", ex.getMostSpecificCause().getMessage());
        return respond(ErrorCode.CONFLICT,
                "The request could not complete because another change was in flight. Please try again.",
                Map.of("retryable", true), null);
    }

    /**
     * A row changed under an optimistic lock. Also safe to retry, and reported the
     * same way, so a client needs one rule for both locking strategies.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLockFailure(OptimisticLockingFailureException ex) {
        log.debug("Optimistic lock failure", ex);
        return respond(ErrorCode.CONFLICT,
                "This record was changed by someone else. Reload and try again.",
                Map.of("retryable", true), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported on this endpoint", null, null);
    }

    /**
     * Anything unhandled. The client learns only that it failed and gets a trace
     * id; the detail goes to the log where it belongs.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString().substring(0, 12);
        log.error("Unhandled exception [traceId={}]", traceId, ex);
        return respond(ErrorCode.INTERNAL,
                "Something went wrong on our side. Quote this reference if you contact support.",
                null, traceId);
    }

    private ResponseEntity<ApiError> respond(
            ErrorCode code, String message, Map<String, Object> details, String traceId) {
        var response = ResponseEntity.status(code.status());
        // A 429 without Retry-After leaves a client guessing, and a client that
        // guesses wrong retries into the same wall.
        if (code == ErrorCode.RATE_LIMITED && details != null
                && details.get("retryAfterSeconds") instanceof Number seconds) {
            response = response.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds.longValue()));
        }
        return response.body(ApiError.of(code, message, details, traceId));
    }
}
