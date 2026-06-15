package com.example.staybooking.domain.booking;

import java.util.EnumSet;
import java.util.Set;

/**
 * 예약 요청 상태 머신 (docs/03).
 *
 * <p>종결 상태가 아닌 모든 상태는 Recovery Job 스캔 대상이다 (불변식 #7).
 */
public enum BookingStatus {

    /** DB 요청 기록 완료. 재고 예약 전이면 짧게만 머무름 */
    RECEIVED,
    /** Redis admission + DB 재고 예약 완료 */
    STOCK_RESERVED,
    /** PG 호출 시작 전 마커 (lease 있음) */
    APPROVING,
    /** PG 승인 확인됨, pg_tx_id 기록됨 */
    APPROVED,
    /** 예약 확정 (= PAID/CONFIRMED 종결) */
    CONFIRMED,
    /** 결제 실패 (사유 포함). 보상 필요 */
    PAYMENT_FAILED,
    /** 보상 진행 중 (CAS 가드 통과) */
    COMPENSATING,
    /** 보상 완료 (종결) */
    FAILED,
    /** 매진/미오픈/게이트 장애 (보상 불요, 종결) */
    REJECTED,
    /** 확정·보상 모두 실패, 수동 개입 대기 */
    RECOVERY_NEEDED;

    private static final Set<BookingStatus> TERMINAL = EnumSet.of(CONFIRMED, FAILED, REJECTED);

    /** 종결 상태(더 이상 진행하지 않음)인지 여부. 비종결은 Recovery Job 대상. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 멱등 재생 시 "처리 중"으로 응답해야 하는 진행 상태 (RECEIVED ~ APPROVED). */
    public boolean isInProgress() {
        return this == RECEIVED || this == STOCK_RESERVED || this == APPROVING || this == APPROVED;
    }
}
