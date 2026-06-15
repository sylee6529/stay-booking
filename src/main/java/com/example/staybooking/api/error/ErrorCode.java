package com.example.staybooking.api.error;

import org.springframework.http.HttpStatus;

/**
 * API 에러 코드 (docs/10). API 응답 코드이자 구조화 로그의 errorCode enum이다 (docs/08).
 *
 * <p>필요한 단계에서 점진적으로 추가한다. 현재는 Checkout(Step 3)에 필요한 항목만 보유.
 */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다."),
    INVALID_PAYMENT_COMBINATION(HttpStatus.BAD_REQUEST, "결제 수단 조합이 올바르지 않습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    PRODUCT_NOT_OPEN(HttpStatus.CONFLICT, "아직 예약할 수 없는 상품입니다."),
    SOLD_OUT(HttpStatus.CONFLICT, "매진되었습니다."),
    RESERVATION_EXPIRED(HttpStatus.CONFLICT, "예약 선점 시간이 만료되었습니다."),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "요청이 처리 중입니다."),
    IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD(HttpStatus.CONFLICT, "같은 멱등키로 다른 요청을 보낼 수 없습니다."),
    STOCK_GATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "재고 게이트를 사용할 수 없습니다."),
    PAYMENT_DECLINED(HttpStatus.UNPROCESSABLE_ENTITY, "결제가 거절되었습니다."),
    INSUFFICIENT_POINT(HttpStatus.UNPROCESSABLE_ENTITY, "포인트 잔액이 부족합니다."),
    BOOKING_CONFIRMATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "예약 확정에 실패했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
