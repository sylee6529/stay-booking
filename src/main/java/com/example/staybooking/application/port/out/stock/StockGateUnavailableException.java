package com.example.staybooking.application.port.out.stock;

/**
 * Redis 연결 실패/타임아웃 또는 stock 키 부재처럼 admission을 신뢰할 수 없는 상태.
 */
public class StockGateUnavailableException extends RuntimeException {

    public StockGateUnavailableException(String message) {
        super(message);
    }

    public StockGateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
