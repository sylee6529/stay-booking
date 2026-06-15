package com.example.staybooking.adapter.out.payment;

import com.example.staybooking.application.port.out.payment.ExternalPaymentGateway;
import com.example.staybooking.application.port.out.payment.GatewayRequest;
import com.example.staybooking.application.port.out.payment.GatewayResult;
import com.example.staybooking.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 입력값으로 성패가 고정되는 결제 시뮬레이터.
 */
@Component
public class SimulatedPaymentGateway implements ExternalPaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final AppProperties properties;

    public SimulatedPaymentGateway(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public GatewayResult approve(GatewayRequest request) {
        delayIfConfigured();
        return switch (request.method()) {
            case CREDIT_CARD -> approveCard(request);
            case Y_PAY -> approveYPay(request);
            default -> throw new IllegalArgumentException("외부 게이트웨이가 처리할 수 없는 수단: " + request.method());
        };
    }

    private GatewayResult approveCard(GatewayRequest request) {
        String digits = request.cardNumber() == null ? "" : request.cardNumber().replaceAll("\\D", "");
        if (digits.endsWith("0000")) {
            return GatewayResult.declined("CARD_DECLINED");
        }
        return GatewayResult.approved("PG-" + UUID.randomUUID());
    }

    private GatewayResult approveYPay(GatewayRequest request) {
        if ("FAIL".equals(request.ypayToken())) {
            return GatewayResult.declined("YPAY_DECLINED");
        }
        return GatewayResult.approved("PG-" + UUID.randomUUID());
    }

    @Override
    public void cancel(String transactionId) {
        log.info("external payment canceled (simulated): transactionId={}", transactionId);
    }

    private void delayIfConfigured() {
        Duration delay = properties.getPgSimulator().getDelay();
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
