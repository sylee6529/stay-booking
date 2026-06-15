package com.example.staybooking.adapter.in.web.error;

/**
 * 공통 에러 응답 봉투 (docs/10). {@code traceId}는 전 흐름 로그와 연결된다 (docs/08).
 */
public record ErrorResponse(String code, String message, String traceId) {
}
