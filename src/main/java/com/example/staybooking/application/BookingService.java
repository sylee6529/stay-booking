package com.example.staybooking.application;

import com.example.staybooking.application.booking.BookingCreateCommand;
import com.example.staybooking.application.booking.BookingCreateResult;
import com.example.staybooking.application.error.BusinessException;
import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.application.payment.PaymentCommand;
import com.example.staybooking.application.payment.PaymentContext;
import com.example.staybooking.application.payment.PaymentExecution;
import com.example.staybooking.application.payment.PaymentOrchestrator;
import com.example.staybooking.domain.booking.Booking;
import com.example.staybooking.domain.booking.BookingRepository;
import com.example.staybooking.domain.booking.BookingRequest;
import com.example.staybooking.domain.booking.BookingRequestRepository;
import com.example.staybooking.domain.booking.BookingStatus;
import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.application.port.out.stock.AdmissionResult;
import com.example.staybooking.application.port.out.stock.StockGatePort;
import com.example.staybooking.application.port.out.stock.StockGateUnavailableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class BookingService {

    private final PromotionProductRepository products;
    private final BookingRequestRepository bookingRequests;
    private final BookingRepository bookings;
    private final StockGatePort stockGate;
    private final PaymentOrchestrator paymentOrchestrator;
    private final BookingRequestHasher hasher;
    private final BookingRequestStore requestStore;
    private final BookingFinalizer finalizer;
    private final BookingCompensationService compensationService;

    public BookingService(PromotionProductRepository products, BookingRequestRepository bookingRequests,
                          BookingRepository bookings, StockGatePort stockGate, PaymentOrchestrator paymentOrchestrator,
                          BookingRequestHasher hasher, BookingRequestStore requestStore,
                          BookingFinalizer finalizer, BookingCompensationService compensationService) {
        this.products = products;
        this.bookingRequests = bookingRequests;
        this.bookings = bookings;
        this.stockGate = stockGate;
        this.paymentOrchestrator = paymentOrchestrator;
        this.hasher = hasher;
        this.requestStore = requestStore;
        this.finalizer = finalizer;
        this.compensationService = compensationService;
    }

    public BookingCreateResult create(String idempotencyKey, BookingCreateCommand request) {
        PromotionProduct product = products.findById(request.productId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!product.isOpen(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_OPEN);
        }

        long amount = product.getPrice();
        PaymentCommand validationCommand = paymentCommand(request, amount, -1L);
        paymentOrchestrator.validate(validationCommand);
        String requestHash = hasher.hash(request, amount);

        AdmissionResult admission = admit(product.getId(), request.userId(), idempotencyKey);
        if (admission.outcome() == AdmissionResult.Outcome.DUPLICATE) {
            return replayOrInProgress(request.userId(), idempotencyKey, requestHash);
        }
        if (admission.outcome() == AdmissionResult.Outcome.SOLD_OUT) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }

        BookingRequest bookingRequest;
        try {
            bookingRequest = requestStore.createAndReserve(idempotencyKey, requestHash, request, amount);
        } catch (DataIntegrityViolationException e) {
            compensationService.releaseRedisBestEffort(product.getId());
            return replayOrInProgress(request.userId(), idempotencyKey, requestHash);
        }

        requestStore.markApproving(bookingRequest.getId());
        PaymentExecution payment = paymentOrchestrator.pay(paymentCommand(request, amount, bookingRequest.getId()));
        if (!payment.success()) {
            requestStore.markPaymentFailed(bookingRequest.getId(), payment.failureCode(), payment.failureReason());
            compensationService.compensate(bookingRequest.getId(), payment.failureCode(), idempotencyKey);
            throw new BusinessException(payment.failureCode(), payment.failureReason());
        }

        requestStore.markApproved(bookingRequest.getId(), payment.transactionId());
        return finalizer.confirm(bookingRequest, product, payment, idempotencyKey);
    }

    private AdmissionResult admit(long productId, long userId, String idempotencyKey) {
        try {
            return stockGate.admit(productId, userId, idempotencyKey);
        } catch (StockGateUnavailableException e) {
            throw new BusinessException(ErrorCode.STOCK_GATE_UNAVAILABLE);
        }
    }

    private BookingCreateResult replayOrInProgress(long userId, String idempotencyKey, String requestHash) {
        BookingRequest existing = bookingRequests.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_IN_PROGRESS));
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD);
        }
        if (existing.getStatus().isInProgress()) {
            throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
        }
        if (existing.getStatus() == BookingStatus.CONFIRMED) {
            Booking booking = bookings.findByBookingRequestId(existing.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_IN_PROGRESS));
            return new BookingCreateResult(booking.getId(), "CONFIRMED");
        }
        throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
    }

    private PaymentCommand paymentCommand(BookingCreateCommand request, long amount, long bookingRequestId) {
        return new PaymentCommand(
                request.paymentMethods(),
                amount,
                request.pointAmount(),
                new PaymentContext(request.userId(), bookingRequestId, request.cardNumber(), request.ypayToken()));
    }
}
