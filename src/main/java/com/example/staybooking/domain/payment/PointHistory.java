package com.example.staybooking.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 포인트 차감/환불 이력. {@code UNIQUE(booking_request_id, type)}가 보상 재실행 시
 * 포인트 이중 환불을 막는다 (불변식 #2, docs/06).
 */
@Entity
@Table(name = "point_history")
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_request_id", nullable = false)
    private Long bookingRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PointHistoryType type;

    @Column(nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PointHistory() {
    }

    public PointHistory(Long bookingRequestId, PointHistoryType type, long amount, LocalDateTime createdAt) {
        this.bookingRequestId = bookingRequestId;
        this.type = type;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getBookingRequestId() {
        return bookingRequestId;
    }

    public PointHistoryType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
