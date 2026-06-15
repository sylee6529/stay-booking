package com.example.staybooking.application;

import com.example.staybooking.application.booking.BookingCreateResult;
import com.example.staybooking.application.error.BusinessException;
import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.domain.booking.Booking;
import com.example.staybooking.domain.booking.BookingRepository;
import com.example.staybooking.domain.booking.BookingRequest;
import com.example.staybooking.domain.booking.BookingRequestRepository;
import com.example.staybooking.domain.booking.BookingStatus;
import com.example.staybooking.domain.payment.Payment;
import com.example.staybooking.domain.payment.PaymentRepository;
import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.application.payment.PaymentExecution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class BookingFinalizer {

    private final PromotionProductRepository products;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final BookingRequestRepository bookingRequests;

    public BookingFinalizer(PromotionProductRepository products, BookingRepository bookings,
                            PaymentRepository payments, BookingRequestRepository bookingRequests) {
        this.products = products;
        this.bookings = bookings;
        this.payments = payments;
        this.bookingRequests = bookingRequests;
    }

    @Transactional
    public BookingCreateResult confirm(BookingRequest request, PromotionProduct product,
                                       PaymentExecution paymentExecution, String traceId) {
        BookingRequest locked = bookingRequests.findByIdForUpdate(request.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_IN_PROGRESS));
        Booking existing = bookings.findByBookingRequestId(request.getId()).orElse(null);
        if (existing != null) {
            return new BookingCreateResult(existing.getId(), existing.getStatus());
        }
        if (locked.getStatus() != BookingStatus.APPROVED) {
            throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
        }

        int confirmed = products.confirmOne(product.getId());
        if (confirmed == 0) {
            throw new BusinessException(ErrorCode.SOLD_OUT, "예약 확정 재고가 없습니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        Booking booking = bookings.save(new Booking(
                request.getId(),
                request.getUserId(),
                request.getProductId(),
                product.getCheckinDate(),
                product.getCheckoutDate(),
                "CONFIRMED",
                now));

        payments.save(new Payment(
                request.getId(),
                paymentExecution.transactionId(),
                paymentExecution.methodsLabel(),
                paymentExecution.totalAmount(),
                paymentExecution.pointAmount(),
                paymentExecution.externalAmount(),
                "APPROVED",
                now));

        BookingCreateResult response = new BookingCreateResult(booking.getId(), "CONFIRMED");
        bookingRequests.complete(request.getId(), BookingStatus.CONFIRMED, 200,
                responseJson(response), now);
        return response;
    }

    private String responseJson(BookingCreateResult response) {
        return """
                {"bookingId":%d,"status":"%s"}
                """.formatted(response.bookingId(), response.status()).trim();
    }

}
