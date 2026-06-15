package com.example.staybooking.application;

import com.example.staybooking.api.dto.BookingCreateRequest;
import com.example.staybooking.api.error.BusinessException;
import com.example.staybooking.api.error.ErrorCode;
import com.example.staybooking.domain.booking.BookingRequest;
import com.example.staybooking.domain.booking.BookingRequestRepository;
import com.example.staybooking.domain.booking.BookingStatus;
import com.example.staybooking.domain.booking.PgStatus;
import com.example.staybooking.domain.product.PromotionProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.Collectors;

@Service
public class BookingRequestStore {

    private static final Duration RESERVATION_TTL = Duration.ofMinutes(3);
    private static final Duration APPROVING_LEASE = Duration.ofSeconds(60);

    private final BookingRequestRepository bookingRequests;
    private final PromotionProductRepository products;

    public BookingRequestStore(BookingRequestRepository bookingRequests, PromotionProductRepository products) {
        this.bookingRequests = bookingRequests;
        this.products = products;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public BookingRequest createAndReserve(String idempotencyKey, String requestHash,
                                           BookingCreateRequest request, long amount) {
        LocalDateTime now = LocalDateTime.now();
        BookingRequest saved = bookingRequests.saveAndFlush(BookingRequest.received(
                idempotencyKey,
                requestHash,
                request.userId(),
                request.productId(),
                methodsLabel(request),
                amount,
                request.pointAmount(),
                now));

        int reserved = products.reserveOne(request.productId());
        if (reserved == 0) {
            String body = """
                    {"code":"SOLD_OUT","message":"매진되었습니다.","traceId":"%s"}
                    """.formatted(idempotencyKey).trim();
            bookingRequests.failTerminal(saved.getId(), BookingStatus.REJECTED, PgStatus.NONE,
                    "SOLD_OUT_DB", 409, body, now);
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }

        bookingRequests.markStockReserved(saved.getId(), BookingStatus.STOCK_RESERVED,
                now.plus(RESERVATION_TTL), now);
        return bookingRequests.findById(saved.getId()).orElseThrow();
    }

    @Transactional
    public void markApproving(Long bookingRequestId) {
        LocalDateTime now = LocalDateTime.now();
        bookingRequests.markApproving(bookingRequestId, BookingStatus.APPROVING, PgStatus.APPROVING,
                now.plus(APPROVING_LEASE), now);
    }

    @Transactional
    public void markApproved(Long bookingRequestId, String pgTxId) {
        bookingRequests.markApproved(bookingRequestId, BookingStatus.APPROVED, PgStatus.APPROVED,
                pgTxId, LocalDateTime.now());
    }

    @Transactional
    public void markPaymentFailed(Long bookingRequestId, ErrorCode code, String reason) {
        bookingRequests.markPaymentFailed(bookingRequestId, BookingStatus.PAYMENT_FAILED, PgStatus.DECLINED,
                reason == null ? code.getMessage() : reason, LocalDateTime.now());
    }

    @Transactional
    public void markFailedTerminal(Long bookingRequestId, ErrorCode code, String reason, String traceId) {
        String body = """
                {"code":"%s","message":"%s","traceId":"%s"}
                """.formatted(code.name(), reason == null ? code.getMessage() : reason, traceId).trim();
        bookingRequests.failTerminal(bookingRequestId, BookingStatus.FAILED, PgStatus.DECLINED,
                reason, code.getStatus().value(), body, LocalDateTime.now());
    }

    private String methodsLabel(BookingCreateRequest request) {
        return request.paymentMethods().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .collect(Collectors.joining("+"));
    }
}
