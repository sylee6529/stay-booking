package com.example.staybooking.application.port.out.payment;

/**
 * 외부 결제 게이트웨이 포트. 실제 PG 연동 대신 시뮬레이터가 구현한다.
 */
public interface ExternalPaymentGateway {

    GatewayResult approve(GatewayRequest request);

    void cancel(String transactionId);
}
