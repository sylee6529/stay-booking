package com.example.staybooking.infra.payment;

import com.example.staybooking.application.payment.port.GatewayRequest;
import com.example.staybooking.application.payment.port.GatewayResult;
import com.example.staybooking.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 시뮬레이터의 결정성 검증 (docs/06). 입력값으로 성패가 고정된다.
 */
class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(new AppProperties());

    @Test
    void 카드번호_끝자리_0000은_거절된다() {
        GatewayResult result = gateway.approve(GatewayRequest.card("4111-1111-1111-0000", 1000));

        assertThat(result.approved()).isFalse();
        assertThat(result.reason()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void 정상_카드번호는_승인되고_PG접두_식별자를_발급한다() {
        GatewayResult result = gateway.approve(GatewayRequest.card("4111-1111-1111-1234", 1000));

        assertThat(result.approved()).isTrue();
        assertThat(result.transactionId()).startsWith("PG-");
    }

    @Test
    void YPay토큰_FAIL은_거절된다() {
        GatewayResult result = gateway.approve(GatewayRequest.ypay("FAIL", 1000));

        assertThat(result.approved()).isFalse();
        assertThat(result.reason()).isEqualTo("YPAY_DECLINED");
    }

    @Test
    void 정상_YPay토큰은_승인된다() {
        GatewayResult result = gateway.approve(GatewayRequest.ypay("valid-token", 1000));

        assertThat(result.approved()).isTrue();
        assertThat(result.transactionId()).startsWith("PG-");
    }
}
