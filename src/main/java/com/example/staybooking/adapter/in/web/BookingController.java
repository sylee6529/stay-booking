package com.example.staybooking.adapter.in.web;

import com.example.staybooking.adapter.in.web.dto.BookingCreateRequest;
import com.example.staybooking.adapter.in.web.dto.BookingCreateResponse;
import com.example.staybooking.application.booking.BookingCreateCommand;
import com.example.staybooking.application.booking.BookingCreateResult;
import com.example.staybooking.application.BookingService;
import com.example.staybooking.application.error.BusinessException;
import com.example.staybooking.application.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/bookings")
    public ResponseEntity<BookingCreateResponse> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BookingCreateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key 헤더가 필요합니다.");
        }
        BookingCreateResult result = bookingService.create(idempotencyKey, toCommand(request));
        return ResponseEntity.ok(new BookingCreateResponse(result.bookingId(), result.status()));
    }

    private BookingCreateCommand toCommand(BookingCreateRequest request) {
        return new BookingCreateCommand(
                request.productId(),
                request.userId(),
                request.paymentMethods(),
                request.pointAmount(),
                request.cardNumber(),
                request.ypayToken());
    }
}
