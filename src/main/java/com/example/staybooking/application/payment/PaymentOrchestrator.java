package com.example.staybooking.application.payment;

import com.example.staybooking.application.error.BusinessException;
import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.domain.payment.PaymentMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 복합 결제 오케스트레이션 (docs/06). 조합 검증 · 금액 분배 · 실행 순서 · 부분 실패 보상을 단독으로 책임진다.
 * 구체 결제 수단(Card/YPay/Point)은 {@link PaymentProcessor} 전략으로만 다루므로 수단 추가가 이 클래스에 새지 않는다.
 *
 * <p>실행 순서: <b>포인트 먼저(자사 DB, 보상 쉬움) → 외부 수단 나중(보상 어려움)</b>.
 * 외부 승인이 거절되면 이미 차감한 포인트를 즉시 환불해, 실패 결과는 "차감된 돈 없음"을 보장한다.
 * (재고 복구 + 상태 전이는 BookingService/Recovery의 Saga 책임이며 여기서 다루지 않는다.)
 */
@Component
public class PaymentOrchestrator {

    /** 수단 라벨/실행의 정규 순서 (docs/08 예: CREDIT_CARD+Y_POINT). */
    private static final List<PaymentMethod> CANONICAL_ORDER =
            List.of(PaymentMethod.CREDIT_CARD, PaymentMethod.Y_PAY, PaymentMethod.Y_POINT);

    private final Map<PaymentMethod, PaymentProcessor> processors = new EnumMap<>(PaymentMethod.class);

    public PaymentOrchestrator(List<PaymentProcessor> processorList) {
        for (PaymentProcessor processor : processorList) {
            processors.put(processor.method(), processor);
        }
    }

    /**
     * 조합/금액 검증 (docs/06). 멱등성 점유 이전에 호출한다 — 형식이 틀린 요청은 상태 기록 없이 즉시 거절.
     *
     * @throws BusinessException INVALID_PAYMENT_COMBINATION / INVALID_REQUEST
     */
    public void validate(PaymentCommand command) {
        List<PaymentMethod> methods = command.methods();
        if (methods == null || methods.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION, "결제 수단이 비어 있습니다.");
        }
        if (methods.contains(PaymentMethod.CREDIT_CARD) && methods.contains(PaymentMethod.Y_PAY)) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION, "신용카드와 Y페이는 함께 사용할 수 없습니다.");
        }

        long amount = command.amount();
        long point = command.pointAmount();
        boolean hasPoint = methods.contains(PaymentMethod.Y_POINT);
        boolean hasExternal = methods.contains(PaymentMethod.CREDIT_CARD) || methods.contains(PaymentMethod.Y_PAY);

        if (hasPoint) {
            if (point <= 0 || point > amount) {
                throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION,
                        "포인트 사용액은 0보다 크고 결제 금액 이하여야 합니다.");
            }
        } else if (point != 0) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION,
                    "포인트 미사용 시 포인트 금액은 0이어야 합니다.");
        }

        if (hasExternal) {
            // 외부 수단이 있으면 외부가 부담할 금액(amount - point)이 반드시 남아야 한다.
            if (amount - point <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION,
                        "외부 결제 수단이 부담할 금액이 없습니다.");
            }
        } else {
            // 포인트 단독: 포인트가 전액을 부담해야 한다.
            if (point != amount) {
                throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION,
                        "포인트 단독 결제는 전액을 포인트로 부담해야 합니다.");
            }
        }

        // 필드 존재 검증 (docs/10)
        if (methods.contains(PaymentMethod.CREDIT_CARD) && !StringUtils.hasText(command.context().cardNumber())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "카드번호가 필요합니다.");
        }
        if (methods.contains(PaymentMethod.Y_PAY) && !StringUtils.hasText(command.context().ypayToken())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Y페이 토큰이 필요합니다.");
        }
    }

    /**
     * 결제 실행. 검증 통과를 전제로 포인트 → 외부 순으로 승인하고, 부분 실패 시 포인트를 환불한다.
     */
    public PaymentExecution pay(PaymentCommand command) {
        validate(command);

        long amount = command.amount();
        long pointAmount = command.pointAmount();
        long externalAmount = amount - pointAmount;
        PaymentContext context = command.context();

        // 1) 포인트 선차감 (보상이 쉬운 쪽 먼저)
        String pointTxId = null;
        if (pointAmount > 0) {
            ProcessorResult result = processor(PaymentMethod.Y_POINT).approve(context, pointAmount);
            if (!result.approved()) {
                return PaymentExecution.failed(result.failureCode(), result.failureReason());
            }
            pointTxId = result.transactionId();
        }

        // 2) 외부 승인 (보상이 어려운 쪽 나중)
        String externalTxId = null;
        if (externalAmount > 0) {
            PaymentMethod externalMethod = externalMethod(command.methods());
            ProcessorResult result = processor(externalMethod).approve(context, externalAmount);
            if (!result.approved()) {
                // 부분 실패 보상: 이미 차감한 포인트 즉시 환불 (멱등)
                if (pointTxId != null) {
                    processor(PaymentMethod.Y_POINT).cancel(context, pointTxId, pointAmount);
                }
                return PaymentExecution.failed(result.failureCode(), result.failureReason());
            }
            externalTxId = result.transactionId();
        }

        String transactionId = externalTxId != null ? externalTxId : pointTxId;
        return PaymentExecution.success(transactionId, label(command.methods()),
                amount, pointAmount, externalAmount);
    }

    private PaymentProcessor processor(PaymentMethod method) {
        PaymentProcessor processor = processors.get(method);
        if (processor == null) {
            throw new IllegalStateException("등록되지 않은 결제 수단: " + method);
        }
        return processor;
    }

    private PaymentMethod externalMethod(List<PaymentMethod> methods) {
        return methods.contains(PaymentMethod.CREDIT_CARD) ? PaymentMethod.CREDIT_CARD : PaymentMethod.Y_PAY;
    }

    private String label(List<PaymentMethod> methods) {
        StringBuilder builder = new StringBuilder();
        for (PaymentMethod method : CANONICAL_ORDER) {
            if (methods.contains(method)) {
                if (builder.length() > 0) {
                    builder.append('+');
                }
                builder.append(method.name());
            }
        }
        return builder.toString();
    }
}
