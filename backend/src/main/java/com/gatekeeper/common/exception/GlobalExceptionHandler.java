package com.gatekeeper.common.exception;

import com.gatekeeper.common.web.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(RateLimitedException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", String.valueOf(Math.max(1L, ex.getRetryAfterSeconds())));
        headers.set("X-RateLimit-Limit", String.valueOf(ex.getLimit()));
        headers.set("X-RateLimit-Remaining", String.valueOf(Math.max(0L, ex.getRemaining())));
        headers.set("X-RateLimit-Reset", String.valueOf(ex.getResetEpochSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(ErrorResponse.of(ex.getMessage(), currentTraceId()));
    }

    @ExceptionHandler(WaitingRoomRequiredException.class)
    public ResponseEntity<ErrorResponse> handleWaitingRoom(WaitingRoomRequiredException ex) {
        HttpHeaders headers = new HttpHeaders();
        if (ex.getQueueToken() != null) {
            headers.set("X-Waiting-Room-Token", ex.getQueueToken());
        }
        headers.set("X-Waiting-Room-Position", String.valueOf(ex.getPosition()));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("eventId", ex.getEventId().toString());
        if (ex.getQueueToken() != null) {
            details.put("queueToken", ex.getQueueToken());
        }
        details.put("position", String.valueOf(ex.getPosition()));
        return ResponseEntity.status(ex.getStatus())
                .headers(headers)
                .body(ErrorResponse.of(ex.getMessage(), details, currentTraceId()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getMessage(), currentTraceId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Validation failed", fieldErrors, currentTraceId()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation ->
                fieldErrors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage())
        );
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Validation failed", fieldErrors, currentTraceId()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Invalid request payload or parameters", currentTraceId()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("Resource was modified concurrently; please retry", currentTraceId()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("Data integrity conflict", currentTraceId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("Access denied", currentTraceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception traceId={}", currentTraceId(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("An unexpected internal error occurred", currentTraceId()));
    }

    public static String currentTraceId() {
        String traceId = MDC.get(RequestIdFilter.MDC_TRACE_ID_KEY);
        return traceId != null ? traceId : "";
    }
}

