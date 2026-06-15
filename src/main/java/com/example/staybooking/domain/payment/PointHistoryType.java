package com.example.staybooking.domain.payment;

/**
 * {@code UNIQUE(booking_request_id, type)}로 차감/환불 중복 반영을 막는다.
 */
public enum PointHistoryType {
    USE,
    REFUND
}
