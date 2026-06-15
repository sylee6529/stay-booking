package com.example.staybooking.application.payment;

public record PaymentContext(long userId, long bookingRequestId, String cardNumber, String ypayToken) {
}
