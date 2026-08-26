package com.hostelops.exception;

import java.time.Instant;
import java.util.Map;

/**
 * The one and only error shape this API emits:
 *
 * <pre>
 * { "error": { "code": "ROOM_FULL", "message": "...", "details": { ... } } }
 * </pre>
 *
 * <p>Null members are omitted from JSON (see {@code spring.jackson}), so a
 * response without extra context carries just {@code code} and {@code message}.
 * A single shape is what lets the generated frontend client handle failures in
 * one place instead of sniffing each endpoint's ad-hoc payload.
 */
public record ApiError(Body error) {

    public record Body(
            String code,
            String message,
            Map<String, Object> details,
            String traceId,
            Instant timestamp) {
    }

    public static ApiError of(ErrorCode code, String message, Map<String, Object> details, String traceId) {
        return new ApiError(new Body(code.name(), message, details, traceId, Instant.now()));
    }
}
