package com.example.staybooking.application.port.out.stock;

/**
 * Redis 재고 게이트를 신뢰할 수 없는 상태 — Fail-Closed 신호 (불변식 #6, docs/07 F1).
 *
 * <p>발생 조건: Redis 연결 실패/타임아웃, {@code stock:} 키 부재(-2).
 * 키 부재를 '무한 재고'나 '매진'으로 해석하지 않고 명시적으로 거절한다.
 * Step 8에서 503 STOCK_GATE_UNAVAILABLE 로 매핑한다.
 */
public class StockGateUnavailableException extends RuntimeException {

    public StockGateUnavailableException(String message) {
        super(message);
    }

    public StockGateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
