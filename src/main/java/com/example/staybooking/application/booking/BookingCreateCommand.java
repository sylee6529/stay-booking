package com.example.staybooking.application.booking;

import com.example.staybooking.domain.payment.PaymentMethod;

import java.util.List;

public record BookingCreateCommand(
        long productId,
        long userId,
        List<PaymentMethod> paymentMethods,
        long pointAmount,
        String cardNumber,
        String ypayToken
) {
}
