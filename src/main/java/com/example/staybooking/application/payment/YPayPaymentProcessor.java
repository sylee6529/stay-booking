package com.example.staybooking.application.payment;

import com.example.staybooking.api.error.ErrorCode;
import com.example.staybooking.domain.payment.PaymentMethod;
import com.example.staybooking.infra.payment.ExternalPaymentGateway;
import com.example.staybooking.infra.payment.GatewayRequest;
import com.example.staybooking.infra.payment.GatewayResult;
import org.springframework.stereotype.Component;

/**
 * Y페이 결제 — 외부 게이트웨이 경유. DB 트랜잭션 없이 호출한다 (불변식 #4).
 */
@Component
public class YPayPaymentProcessor implements PaymentProcessor {

    private final ExternalPaymentGateway gateway;

    public YPayPaymentProcessor(ExternalPaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.Y_PAY;
    }

    @Override
    public ProcessorResult approve(PaymentContext context, long amount) {
        GatewayResult result = gateway.approve(GatewayRequest.ypay(context.ypayToken(), amount));
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
