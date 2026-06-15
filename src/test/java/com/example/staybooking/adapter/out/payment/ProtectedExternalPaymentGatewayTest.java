package com.example.staybooking.adapter.out.payment;

import com.example.staybooking.application.port.out.payment.GatewayRequest;
import com.example.staybooking.application.port.out.payment.GatewayResult;
import com.example.staybooking.config.AppProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectedExternalPaymentGatewayTest {

    @Test
    void PG_실패가_반복되면_CircuitBreaker가_열리고_후속호출을_빠르게_거절한다() {
        AppProperties properties = new AppProperties();
        properties.getPgProtection().setCircuitSlidingWindowSize(5);
        properties.getPgProtection().setCircuitMinimumNumberOfCalls(5);
        properties.getPgProtection().setCircuitFailureRateThreshold(50);
        properties.getPgProtection().setCircuitOpenDuration(Duration.ofSeconds(30));
        ProtectedExternalPaymentGateway gateway =
                new ProtectedExternalPaymentGateway(new SimulatedPaymentGateway(properties), properties);
        GatewayRequest declined = GatewayRequest.card("4111-1111-1111-0000", 150000);

        for (int i = 0; i < 5; i++) {
            GatewayResult result = gateway.approve(declined);
            assertThat(result.approved()).isFalse();
        }

        assertThat(gateway.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        GatewayResult shortCircuited = gateway.approve(GatewayRequest.card("4111-1111-1111-1234", 150000));

        assertThat(shortCircuited.approved()).isFalse();
        assertThat(shortCircuited.reason()).isEqualTo("PG_CIRCUIT_OPEN");
    }

    @Test
    void PG_응답이_타임아웃보다_느리면_PG_TIMEOUT으로_거절한다() {
        AppProperties properties = new AppProperties();
        properties.getPgProtection().setTimeout(Duration.ofMillis(50));
        properties.getPgProtection().setCircuitMinimumNumberOfCalls(100);
        properties.getPgSimulator().setDelay(Duration.ofMillis(200));
        ProtectedExternalPaymentGateway gateway =
                new ProtectedExternalPaymentGateway(new SimulatedPaymentGateway(properties), properties);

        GatewayResult result = gateway.approve(GatewayRequest.card("4111-1111-1111-1234", 150000));

        assertThat(result.approved()).isFalse();
        assertThat(result.reason()).isEqualTo("PG_TIMEOUT");
    }
}
