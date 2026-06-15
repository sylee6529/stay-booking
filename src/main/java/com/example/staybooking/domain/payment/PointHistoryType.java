package com.example.staybooking.domain.payment;

/**
 * 포인트 이력 유형. {@code UNIQUE(booking_request_id, type)}로 USE/REFUND 중복 반영을 막는다
 * (불변식 #2, docs/06).
 */
public enum PointHistoryType {
    /** 포인트 차감 */
    USE,
    /** 보상 시 포인트 환불 */
    REFUND
}
