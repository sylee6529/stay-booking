package com.example.staybooking.domain.booking;

/**
 * 외부 PG 결제 진행 상태 (docs/03 step A~C, docs/08). booking_requests.pg_status 에 영속화한다.
 */
public enum PgStatus {
    /** PG 호출 전 */
    NONE,
    /** PG 호출 직전 마커 (lease 있음) */
    APPROVING,
    /** PG 승인 확인됨 (pg_tx_id 있음) */
    APPROVED,
    /** PG 승인 거절 */
    DECLINED,
    /** lease 만료 등으로 승인 여부 불명 — 현재 구현은 성공으로 보지 않고 보상한다 */
    IN_DOUBT,
    /** 보상으로 승인 취소됨 */
    CANCELED
}
