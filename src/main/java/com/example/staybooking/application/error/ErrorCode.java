package com.example.staybooking.application.error;

public enum ErrorCode {

    INVALID_REQUEST(400, "요청이 올바르지 않습니다."),
    INVALID_PAYMENT_COMBINATION(400, "결제 수단 조합이 올바르지 않습니다."),
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    PRODUCT_NOT_OPEN(409, "아직 예약할 수 없는 상품입니다."),
    SOLD_OUT(409, "매진되었습니다."),
    RESERVATION_EXPIRED(409, "예약 선점 시간이 만료되었습니다."),
    REQUEST_IN_PROGRESS(409, "요청이 처리 중입니다."),
    IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD(409, "같은 멱등키로 다른 요청을 보낼 수 없습니다."),
    RATE_LIMITED(429, "요청이 너무 많습니다."),
    STOCK_GATE_UNAVAILABLE(503, "재고 게이트를 사용할 수 없습니다."),
    PAYMENT_DECLINED(422, "결제가 거절되었습니다."),
    INSUFFICIENT_POINT(422, "포인트 잔액이 부족합니다."),
    BOOKING_CONFIRMATION_FAILED(500, "예약 확정에 실패했습니다.");

    private final int httpStatus;
    private final String message;

    ErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
