package com.example.staybooking.domain.booking;

import java.util.EnumSet;
import java.util.Set;

public enum BookingStatus {

    RECEIVED,
    STOCK_RESERVED,
    /** PG 호출 시작 전 마커 (lease 있음) */
    APPROVING,
    APPROVED,
    CONFIRMED,
    PAYMENT_FAILED,
    COMPENSATING,
    FAILED,
    REJECTED,
    /** 확정·보상 모두 실패, 수동 개입 대기 */
    RECOVERY_NEEDED;

    private static final Set<BookingStatus> TERMINAL = EnumSet.of(CONFIRMED, FAILED, REJECTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isInProgress() {
        return this == RECEIVED || this == STOCK_RESERVED || this == APPROVING || this == APPROVED;
    }
}
