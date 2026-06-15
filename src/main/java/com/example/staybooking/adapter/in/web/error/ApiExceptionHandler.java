package com.example.staybooking.adapter.in.web.error;

import com.example.staybooking.application.error.BusinessException;
import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.application.error.IdempotencyReplayException;
import com.example.staybooking.application.error.RateLimitExceededException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        ErrorResponse body = new ErrorResponse(code.name(), e.getMessage(), newTraceId());
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException e) {
        ErrorCode code = e.getErrorCode();
        ErrorResponse body = new ErrorResponse(code.name(), e.getMessage(), newTraceId());
        return ResponseEntity.status(code.getHttpStatus())
                .header("Retry-After", Long.toString(e.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(IdempotencyReplayException.class)
    public ResponseEntity<String> handleIdempotencyReplay(IdempotencyReplayException e) {
        return ResponseEntity.status(e.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(e.getResponseBody());
    }

    private String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
