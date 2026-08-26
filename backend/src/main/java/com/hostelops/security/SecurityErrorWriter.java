package com.hostelops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.exception.ApiError;
import com.hostelops.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

/**
 * Writes the {@link ApiError} envelope from inside the filter chain.
 *
 * <p>{@code @RestControllerAdvice} only sees exceptions raised by a handler
 * method. Security rejects a request before dispatch, so without this the
 * client would get Spring's default HTML error page for 401 and 403 while every
 * other failure arrived as JSON -- and a typed client cannot parse two shapes.
 */
final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(code, message, null, null));
    }
}
