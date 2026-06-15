package com.example.staybooking.application.payment;

import com.example.staybooking.domain.payment.PaymentMethod;

/**
 * 결제 수단 전략 (docs/06). 각 구현체는 "한 수단의 승인/취소"만 안다.
 * 조합 규칙·실행 순서·보상은 {@link PaymentOrchestrator}가 단독으로 책임진다.
 *
 * <p>수단 추가 시 이 인터페이스 구현체 1개만 추가하면 된다 (OCP, 불변식: 결제 확장성).
 */
public interface PaymentProcessor {

    PaymentMethod method();

    /** 지정 금액 승인. */
    ProcessorResult approve(PaymentContext context, long amount);

    /**
     * 승인 취소/환불 (보상). 멱등해야 한다 — 같은 건에 두 번 호출돼도 최종 효과는 1회.
     *
     * @param transactionId approve가 발급한 식별자 (포인트는 사용 안 함)
     */
    void cancel(PaymentContext context, String transactionId, long amount);
}
