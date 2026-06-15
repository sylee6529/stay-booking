package com.example.staybooking.domain.booking;

/**
 * 보상 시 Redis 재고 복구 상태 (docs/06, docs/11).
 *
 * <p>중복 INCR이 가장 위험하므로 boolean 대신 3-상태로 추적한다.
 * 불명확하면 {@link #NEEDS_SYNC}로 남기고 DB 기준 stock sync가 회복한다 (불변식 #5).
 */
public enum StockRestoreStatus {
    /** 재고 보상 대상이 아니거나 아직 처리 전 */
    NONE,
    /** Redis INCR 성공 또는 이후 stock sync 완료 */
    SYNCED,
    /** Redis INCR 실패/불명확. 중복 INCR 금지, DB 기준 stock sync 필요 */
    NEEDS_SYNC
}
