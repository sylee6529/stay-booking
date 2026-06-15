package com.example.staybooking.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_request_id", nullable = false)
    private Long bookingRequestId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "payment_methods", nullable = false, length = 40)
    private String paymentMethods;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "point_amount", nullable = false)
    private long pointAmount;

    @Column(name = "external_amount", nullable = false)
    private long externalAmount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Payment() {
    }

    public Payment(Long bookingRequestId, String transactionId, String paymentMethods,
                   long totalAmount, long pointAmount, long externalAmount, String status,
                   LocalDateTime createdAt) {
        this.bookingRequestId = bookingRequestId;
        this.transactionId = transactionId;
        this.paymentMethods = paymentMethods;
        this.totalAmount = totalAmount;
        this.pointAmount = pointAmount;
        this.externalAmount = externalAmount;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getBookingRequestId() {
        return bookingRequestId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getPaymentMethods() {
        return paymentMethods;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public long getPointAmount() {
        return pointAmount;
    }

    public long getExternalAmount() {
        return externalAmount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
