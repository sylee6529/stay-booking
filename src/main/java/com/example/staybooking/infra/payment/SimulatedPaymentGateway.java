package com.example.staybooking.infra.payment;

import com.example.staybooking.application.payment.port.ExternalPaymentGateway;
import com.example.staybooking.application.payment.port.GatewayRequest;
import com.example.staybooking.application.payment.port.GatewayResult;
import com.example.staybooking.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 결정적 결제 시뮬레이터 (docs/06). 랜덤 실패 대신 입력값으로 성패를 결정해 테스트 재현성을 보장한다.
 *
 * <ul>
 *   <li>카드번호 끝 4자리 {@code 0000} → CARD_DECLINED</li>
 *   <li>YPay 토큰 {@code FAIL} → YPAY_DECLINED</li>
 *   <li>그 외 → 승인 (PG- 접두 transactionId 발급)</li>
 * </ul>
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
        // 시뮬레이터: 실제 취소 호출 대신 기록만 남긴다. best-effort.
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
