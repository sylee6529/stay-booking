package com.example.staybooking.application.payment;

import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.domain.payment.PaymentMethod;
import com.example.staybooking.application.port.out.payment.ExternalPaymentGateway;
import com.example.staybooking.application.port.out.payment.GatewayRequest;
import com.example.staybooking.application.port.out.payment.GatewayResult;
import org.springframework.stereotype.Component;

/**
 * 신용카드 결제 — 외부 게이트웨이 경유. DB 트랜잭션 없이 호출한다 (불변식 #4).
 */
@Component
public class CreditCardPaymentProcessor implements PaymentProcessor {

    private final ExternalPaymentGateway gateway;

    public CreditCardPaymentProcessor(ExternalPaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CREDIT_CARD;
    }

    @Override
    public ProcessorResult approve(PaymentContext context, long amount) {
        GatewayResult result = gateway.approve(GatewayRequest.card(context.cardNumber(), amount));
        if (!result.approved()) {
            return ProcessorResult.declined(ErrorCode.PAYMENT_DECLINED, result.reason());
        }
        return ProcessorResult.approved(result.transactionId());
    }

    @Override
    public void cancel(PaymentContext context, String transactionId, long amount) {
        gateway.cancel(transactionId);
    }
}
