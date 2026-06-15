package com.example.staybooking.infra.payment;

import com.example.staybooking.application.payment.port.GatewayRequest;
import com.example.staybooking.application.payment.port.GatewayResult;
import com.example.staybooking.config.AppProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectedExternalPaymentGatewayTest {

    @Test
    void PG_실패가_반복되면_CircuitBreaker가_열리고_후속호출을_빠르게_거절한다() {
        AppProperties properties = new AppProperties();
        properties.getPgProtection().setCircuitSlidingWindowSize(5);
        properties.getPgProtection().setCircuitMinimumNumberOfCalls(5);
        properties.getPgProtection().setCircuitFailureRateThreshold(50);
        properties.getPgProtection().setCircuitOpenDuration(java.time.Duration.ofSeconds(30));
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
}
