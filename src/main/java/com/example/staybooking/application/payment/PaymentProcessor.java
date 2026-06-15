package com.example.staybooking.application.payment;

import com.example.staybooking.domain.payment.PaymentMethod;

/**
 * 단일 결제 수단의 승인/취소 전략.
 */
public interface PaymentProcessor {

    PaymentMethod method();

    ProcessorResult approve(PaymentContext context, long amount);

    /**
     * 보상 경로에서 호출되므로 같은 건에 두 번 호출돼도 최종 효과는 1회여야 한다.
     */
    void cancel(PaymentContext context, String transactionId, long amount);
}
