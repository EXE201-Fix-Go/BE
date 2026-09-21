package com.fixgo.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> business(ApiException ex, HttpServletRequest request) {
        return response(ex.getStatus().value(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var fields = new LinkedHashMap<String, String>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, "VALIDATION_ERROR",
                "Please check the submitted fields.", request.getRequestURI(), fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class})
    ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "Invalid request body or parameter.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> duplicate(DataIntegrityViolationException ex, HttpServletRequest request) {
        return response(409, "DATA_CONFLICT", "The data conflicts with an existing record.", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> optimistic(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return response(409, "CONCURRENT_UPDATE", "The record was modified by someone else. Reload and retry.", request);
    }

    @ExceptionHandler(InvalidBearerTokenException.class)
    ResponseEntity<ApiError> unauthenticated(InvalidBearerTokenException ex, HttpServletRequest request) {
        return response(401, "UNAUTHORIZED", "A valid access token is required.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException ex, HttpServletRequest request) {
        return response(403, "FORBIDDEN", "You do not have permission to perform this action.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException ex, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "The requested resource does not exist.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return response(405, "METHOD_NOT_ALLOWED", "This HTTP method is not supported.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API error on {}", request.getRequestURI(), ex);
        return response(500, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }

    private ResponseEntity<ApiError> response(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(status, code, message, request.getRequestURI()));
    }
}
