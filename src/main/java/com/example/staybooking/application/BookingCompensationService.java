package com.example.staybooking.application;

import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.application.payment.PaymentContext;
import com.example.staybooking.application.payment.PointPaymentProcessor;
import com.example.staybooking.domain.booking.BookingRequest;
import com.example.staybooking.domain.booking.BookingRequestRepository;
import com.example.staybooking.domain.booking.BookingStatus;
import com.example.staybooking.domain.booking.PgStatus;
import com.example.staybooking.domain.booking.StockRestoreStatus;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.application.stock.StockGatePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class BookingCompensationService {

    private final PromotionProductRepository products;
    private final StockGatePort stockGate;
    private final BookingRequestRepository bookingRequests;
    private final PointPaymentProcessor pointPaymentProcessor;

    public BookingCompensationService(PromotionProductRepository products, StockGatePort stockGate,
                                      BookingRequestRepository bookingRequests,
                                      PointPaymentProcessor pointPaymentProcessor) {
        this.products = products;
        this.stockGate = stockGate;
        this.bookingRequests = bookingRequests;
        this.pointPaymentProcessor = pointPaymentProcessor;
    }

    @Transactional
    public void releaseDbReserved(long productId) {
        products.releaseOne(productId);
    }

    public void releaseRedisBestEffort(long productId) {
        stockGate.tryRelease(productId);
    }

    @Transactional
    public void compensate(Long bookingRequestId, ErrorCode responseCode, String traceId) {
        BookingRequest request = bookingRequests.findById(bookingRequestId).orElseThrow();
        if (request.getStatus() == BookingStatus.PAYMENT_FAILED) {
            int acquired = bookingRequests.compareAndSetStatus(
                    request.getId(), BookingStatus.PAYMENT_FAILED, BookingStatus.COMPENSATING, LocalDateTime.now());
            if (acquired == 0) {
                return;
            }
        } else if (request.getStatus() != BookingStatus.COMPENSATING) {
            return;
        }

        BookingRequest compensating = bookingRequests.findById(bookingRequestId).orElseThrow();
        if (compensating.getPointAmount() > 0 && !compensating.isPointsRefunded()) {
            pointPaymentProcessor.cancel(
                    new PaymentContext(compensating.getUserId(), compensating.getId(), null, null),
                    "POINT-COMPENSATION",
                    compensating.getPointAmount());
            bookingRequests.markPointsRefunded(compensating.getId(), LocalDateTime.now());
        }

        releaseDbReserved(compensating.getProductId());
        boolean redisRestored = stockGate.tryRelease(compensating.getProductId());
        bookingRequests.markStockRestored(compensating.getId(),
                redisRestored ? StockRestoreStatus.SYNCED : StockRestoreStatus.NEEDS_SYNC,
                LocalDateTime.now());

        String body = """
                {"code":"%s","message":"%s","traceId":"%s"}
                """.formatted(responseCode.name(), responseCode.getMessage(), traceId).trim();
        bookingRequests.failTerminal(compensating.getId(), BookingStatus.FAILED, PgStatus.DECLINED,
                compensating.getFailureReason(), responseCode.getHttpStatus(), body, LocalDateTime.now());
    }
}
