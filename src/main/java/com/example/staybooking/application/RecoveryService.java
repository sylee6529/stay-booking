package com.example.staybooking.application;
import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.application.payment.PaymentExecution;
import com.example.staybooking.config.AppProperties;
import com.example.staybooking.domain.booking.BookingRequest;
import com.example.staybooking.domain.booking.BookingRequestRepository;
import com.example.staybooking.domain.booking.BookingStatus;
import com.example.staybooking.domain.booking.PgStatus;
import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RecoveryService {

    private final BookingRequestRepository bookingRequests;
    private final PromotionProductRepository products;
    private final BookingCompensationService compensationService;
    private final BookingFinalizer finalizer;
    private final AppProperties properties;

    public RecoveryService(BookingRequestRepository bookingRequests, PromotionProductRepository products,
                           BookingCompensationService compensationService, BookingFinalizer finalizer,
                           AppProperties properties) {
        this.bookingRequests = bookingRequests;
        this.products = products;
        this.compensationService = compensationService;
        this.finalizer = finalizer;
        this.properties = properties;
    }

    @Transactional
    public void recoverOnce() {
        LocalDateTime now = LocalDateTime.now();
        recoverExpiredStockReservations(now);
        recoverExpiredApproving(now);
        recoverApprovedStuck(now);
        recoverCompensating();
        recoverNeeded();
    }

    private void recoverExpiredStockReservations(LocalDateTime now) {
        for (BookingRequest request : bookingRequests.findExpiredReservations(BookingStatus.STOCK_RESERVED, now)) {
            int acquired = bookingRequests.compareAndSetStatus(
                    request.getId(), BookingStatus.STOCK_RESERVED, BookingStatus.COMPENSATING, now);
            if (acquired == 0) {
                continue;
            }
            compensationService.releaseDbReserved(request.getProductId());
            compensationService.releaseRedisBestEffort(request.getProductId());
            String body = """
                    {"code":"%s","message":"%s","traceId":"%s"}
                    """.formatted(ErrorCode.RESERVATION_EXPIRED.name(),
                    ErrorCode.RESERVATION_EXPIRED.getMessage(), request.getIdempotencyKey()).trim();
            bookingRequests.failTerminal(request.getId(), BookingStatus.REJECTED, PgStatus.NONE,
                    "RESERVATION_EXPIRED", ErrorCode.RESERVATION_EXPIRED.getHttpStatus(), body, now);
        }
    }

    private void recoverApprovedStuck(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(properties.getRecovery().getStuckThreshold());
        for (BookingRequest request : bookingRequests.findApprovedStuck(BookingStatus.APPROVED, cutoff)) {
            PromotionProduct product = products.findById(request.getProductId()).orElse(null);
            if (product == null) {
                continue;
            }
            PaymentExecution payment = PaymentExecution.success(
                    request.getPgTxId(),
                    request.getPaymentMethods(),
                    request.getAmount(),
                    request.getPointAmount(),
                    request.getAmount() - request.getPointAmount());
            finalizer.confirm(request, product, payment, request.getIdempotencyKey());
        }
    }

    private void recoverExpiredApproving(LocalDateTime now) {
        for (BookingRequest request : bookingRequests.findExpiredApproving(BookingStatus.APPROVING, now)) {
            int acquired = bookingRequests.markPaymentFailedFromStatus(request.getId(), BookingStatus.APPROVING,
                    BookingStatus.PAYMENT_FAILED, PgStatus.IN_DOUBT, "APPROVING_LEASE_EXPIRED", now);
            if (acquired == 0) {
                continue;
            }
            compensationService.compensate(request.getId(), ErrorCode.PAYMENT_DECLINED, request.getIdempotencyKey());
        }
    }

    private void recoverCompensating() {
        for (BookingRequest request : bookingRequests.findByStatus(BookingStatus.COMPENSATING)) {
            compensationService.compensate(request.getId(), ErrorCode.PAYMENT_DECLINED, request.getIdempotencyKey());
        }
    }

    private void recoverNeeded() {
        for (BookingRequest request : bookingRequests.findByStatus(BookingStatus.RECOVERY_NEEDED)) {
            if (request.getPgTxId() != null) {
                PromotionProduct product = products.findById(request.getProductId()).orElse(null);
                if (product != null) {
                    PaymentExecution payment = PaymentExecution.success(
                            request.getPgTxId(),
                            request.getPaymentMethods(),
                            request.getAmount(),
                            request.getPointAmount(),
                            request.getAmount() - request.getPointAmount());
                    finalizer.confirm(request, product, payment, request.getIdempotencyKey());
                }
            } else {
                int acquired = bookingRequests.markPaymentFailedFromStatus(request.getId(), BookingStatus.RECOVERY_NEEDED,
                        BookingStatus.PAYMENT_FAILED, PgStatus.IN_DOUBT,
                        "RECOVERY_NEEDED_WITHOUT_PG_TX", LocalDateTime.now());
                if (acquired == 1) {
                    compensationService.compensate(request.getId(), ErrorCode.PAYMENT_DECLINED, request.getIdempotencyKey());
                }
            }
        }
    }
}
