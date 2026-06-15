package com.example.staybooking.domain.booking;

public enum PgStatus {
    NONE,
    /** PG 호출 직전 마커 (lease 있음) */
    APPROVING,
    APPROVED,
    DECLINED,
    /** lease 만료 등으로 승인 여부 불명 — 현재 구현은 성공으로 보지 않고 보상한다 */
    IN_DOUBT,
    CANCELED
}
