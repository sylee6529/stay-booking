package com.example.staybooking.application.payment;

import com.example.staybooking.domain.payment.PaymentMethod;

import java.util.List;

public record PaymentCommand(List<PaymentMethod> methods, long amount, long pointAmount, PaymentContext context) {
}
