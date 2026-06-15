package com.example.staybooking.infra.payment;

import com.example.staybooking.domain.payment.PaymentMethod;

/**
 * 외부 게이트웨이 승인 요청. method 에 따라 cardNumber 또는 ypayToken 중 하나가 채워진다.
 */
public record GatewayRequest(PaymentMethod method, String cardNumber, String ypayToken, long amount) {

    public static GatewayRequest card(String cardNumber, long amount) {
        return new GatewayRequest(PaymentMethod.CREDIT_CARD, cardNumber, null, amount);
    }

    public static GatewayRequest ypay(String ypayToken, long amount) {
        return new GatewayRequest(PaymentMethod.Y_PAY, null, ypayToken, amount);
    }
}
