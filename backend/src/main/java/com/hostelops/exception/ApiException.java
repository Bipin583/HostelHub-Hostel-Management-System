package com.hostelops.exception;

import java.util.Map;

/**
 * Base class for failures that carry a deliberate public {@link ErrorCode}.
 *
 * <p>The message on an {@code ApiException} is written to be shown to a user.
 * Anything that is *not* an {@code ApiException} is treated as a bug by
 * {@link GlobalExceptionHandler}: it is logged with a trace id and reported to
 * the client as a bare 500, because internal exception text is not a contract
 * and must never reach a caller.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    // -- Convenience factories for the cases used most -----------------------

    public static ApiException notFound(String what, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " " + id + " was not found",
                Map.of("resource", what, "id", String.valueOf(id)));
    }

    public static ApiException outOfScope(String what) {
        return new ApiException(ErrorCode.OUT_OF_SCOPE,
                "This " + what + " is outside the hostel you are responsible for");
    }

    public static ApiException conflict(ErrorCode code, String message) {
        return new ApiException(code, message);
    }
}
