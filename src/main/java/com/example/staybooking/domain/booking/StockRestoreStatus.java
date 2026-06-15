package com.example.staybooking.domain.booking;

/**
 * 중복 INCR을 피하기 위해 Redis 재고 복구 결과를 3-상태로 남긴다.
 */
public enum StockRestoreStatus {
    NONE,
    SYNCED,
    /** Redis INCR 실패/불명확. 재시도하지 않고 DB 기준 sync가 회복한다. */
    NEEDS_SYNC
}
