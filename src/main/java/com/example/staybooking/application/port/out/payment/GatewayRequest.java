package com.example.staybooking.application.port.out.payment;

import com.example.staybooking.domain.payment.PaymentMethod;

public record GatewayRequest(PaymentMethod method, String cardNumber, String ypayToken, long amount) {

    public static GatewayRequest card(String cardNumber, long amount) {
        return new GatewayRequest(PaymentMethod.CREDIT_CARD, cardNumber, null, amount);
    }

    public static GatewayRequest ypay(String ypayToken, long amount) {
        return new GatewayRequest(PaymentMethod.Y_PAY, null, ypayToken, amount);
    }
}
