package com.gatekeeper.common.exception;

import java.util.Map;

public record ErrorResponse(
        String message,
        Map<String, String> fieldErrors,
        String traceId
) {
    public static ErrorResponse of(String message, String traceId) {
        return new ErrorResponse(message, Map.of(), traceId);
    }

    public static ErrorResponse of(String message, Map<String, String> fieldErrors, String traceId) {
        return new ErrorResponse(message, fieldErrors == null ? Map.of() : fieldErrors, traceId);
    }
}

