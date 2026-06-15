package com.example.staybooking.application.port.out.stock;

/**
 * Redis admission 결과 (docs/03 [1], docs/04).
 *
 * <p>키 부재/연결 실패는 결과값이 아니라 {@link StockGateUnavailableException}로 던진다 (Fail-Closed).
 */
public record AdmissionResult(Outcome outcome, long remaining) {

    public enum Outcome {
        /** 선점 성공. {@link #remaining}에 남은 재고 */
        RESERVED,
        /** 매진 (stock <= 0) */
        SOLD_OUT,
        /** 같은 (userId, idempotencyKey) admission 중복 — 멱등 재생 경로로 처리 */
        DUPLICATE
    }

    public static AdmissionResult reserved(long remaining) {
        return new AdmissionResult(Outcome.RESERVED, remaining);
    }

    public static AdmissionResult soldOut() {
        return new AdmissionResult(Outcome.SOLD_OUT, -1);
    }

    public static AdmissionResult duplicate() {
        return new AdmissionResult(Outcome.DUPLICATE, -1);
    }

    public boolean isReserved() {
        return outcome == Outcome.RESERVED;
    }
}
