package com.example.staybooking.api.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * 예외 → HTTP 에러 봉투 매핑 (docs/10). Step 8에서 결제/멱등/Fail-Closed 등으로 확장한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        ErrorResponse body = new ErrorResponse(code.name(), e.getMessage(), newTraceId());
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    private String newTraceId() {
        // Checkout 등 멱등키가 없는 요청은 요청 단위 traceId를 생성한다 (docs/08).
        return UUID.randomUUID().toString();
    }
}
