package com.example.staybooking.infra.payment;

/**
 * 외부 결제 게이트웨이 추상 (docs/06). 실제 PG 연동은 범위 외이며 시뮬레이터로 대체하되,
 * 인터페이스는 실제 외부 의존성 형태(승인/취소)를 유지한다.
 *
 * <p>이 호출은 절대 DB 트랜잭션 안에서 일어나지 않는다 (불변식 #4).
 */
public interface ExternalPaymentGateway {

    GatewayResult approve(GatewayRequest request);

    /** 보상 시 외부 승인 취소. best-effort. */
    void cancel(String transactionId);
}
