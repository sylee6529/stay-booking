package com.example.staybooking.application.port.out.stock;

/**
 * Redis admission 결과. 키 부재/연결 실패는 예외로 처리한다.
 */
public record AdmissionResult(Outcome outcome, long remaining) {

    public enum Outcome {
        RESERVED,
        SOLD_OUT,
        /** 같은 userId/idempotencyKey로 이미 admission을 통과한 요청 */
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
