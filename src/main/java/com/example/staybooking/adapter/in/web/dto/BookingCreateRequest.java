package com.example.staybooking.adapter.in.web.dto;

import com.example.staybooking.domain.payment.PaymentMethod;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record BookingCreateRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Long userId,
        @NotEmpty List<PaymentMethod> paymentMethods,
        @PositiveOrZero long pointAmount,
        String cardNumber,
        String ypayToken
) {
}
