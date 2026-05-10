package com.microservices.kitchen.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException e, HttpServletRequest req) {
        return errorResponse(HttpStatus.NOT_FOUND, "Not Found", e.getMessage(), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e, HttpServletRequest req) {
        return errorResponse(HttpStatus.BAD_REQUEST, "Bad Request", e.getMessage(), req);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e, HttpServletRequest req) {
        return errorResponse(HttpStatus.CONFLICT, "Conflict", e.getMessage(), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e, HttpServletRequest req) {
        return errorResponse(HttpStatus.NOT_FOUND, "Not Found", e.getMessage(), req);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e, HttpServletRequest req) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        String reason = e.getReason() != null ? e.getReason() : status.getReasonPhrase();
        return errorResponse(status, status.getReasonPhrase(), reason, req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "Validation Failed", req);
        body.put("message", "One or more fields are invalid");
        body.put("fields", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e, HttpServletRequest req) {
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String error, String message, HttpServletRequest req) {
        Map<String, Object> body = baseBody(status, error, req);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> baseBody(HttpStatus status, String error, HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("path", req.getRequestURI());
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
