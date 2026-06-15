package com.example.staybooking.domain.booking;

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
 * 멱등성·상태 머신의 진실.
 *
 * <p>상태 전이는 가능한 한 {@link BookingRequestRepository#compareAndSetStatus}(조건부 UPDATE)로
 * 수행한다. 엔티티 dirty-checking에 의존하면 보상 경로가 중복 진입할 수 있다.
 */
@Entity
@Table(name = "booking_requests")
public class BookingRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "payment_methods", nullable = false, length = 40)
    private String paymentMethods;

    @Column(nullable = false)
    private long amount;

    @Column(name = "point_amount", nullable = false)
    private long pointAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "pg_status", nullable = false, length = 20)
    private PgStatus pgStatus = PgStatus.NONE;

    @Column(name = "pg_tx_id", length = 64)
    private String pgTxId;

    @Column(name = "reservation_expires_at")
    private LocalDateTime reservationExpiresAt;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "points_refunded", nullable = false)
    private boolean pointsRefunded = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_restore_status", nullable = false, length = 20)
    private StockRestoreStatus stockRestoreStatus = StockRestoreStatus.NONE;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "response_body", columnDefinition = "json")
    private String responseBody;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected BookingRequest() {
    }

    private BookingRequest(String idempotencyKey, String requestHash, Long userId, Long productId,
                           String paymentMethods, long amount, long pointAmount, LocalDateTime now) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.userId = userId;
        this.productId = productId;
        this.paymentMethods = paymentMethods;
        this.amount = amount;
        this.pointAmount = pointAmount;
        this.status = BookingStatus.RECEIVED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static BookingRequest received(String idempotencyKey, String requestHash, Long userId,
                                          Long productId, String paymentMethods, long amount,
                                          long pointAmount, LocalDateTime now) {
        return new BookingRequest(idempotencyKey, requestHash, userId, productId,
                paymentMethods, amount, pointAmount, now);
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }

    public String getPaymentMethods() {
        return paymentMethods;
    }

    public long getAmount() {
        return amount;
    }

    public long getPointAmount() {
        return pointAmount;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public PgStatus getPgStatus() {
        return pgStatus;
    }

    public String getPgTxId() {
        return pgTxId;
    }

    public LocalDateTime getReservationExpiresAt() {
        return reservationExpiresAt;
    }

    public LocalDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public boolean isPointsRefunded() {
        return pointsRefunded;
    }

    public StockRestoreStatus getStockRestoreStatus() {
        return stockRestoreStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
