package com.aigre.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Spring Boot 4's default WebFlux error body doesn't include a "message" field even with
 * server.error.include-message=always (confirmed empirically: the default body was
 * {timestamp,path,status,error,requestId}, no message) -- taking direct control instead of
 * fighting the default so the frontend actually has something to show the user.
 *
 * IllegalArgumentException means "bad grievance ID" everywhere it's thrown in this codebase
 * (GrievanceMcpTools); IllegalStateException means "no paused workflow run to act on"
 * (GrievanceWorkflowService). Centralized here rather than duplicated per-controller.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
