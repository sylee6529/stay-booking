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
 * 결제 조합 검증, 금액 분배, 부분 실패 보상을 조정한다.
 *
 * <p>포인트는 외부 결제보다 먼저 차감한다. 외부 승인이 거절되면 포인트를 즉시 환불한다.
 */
@Component
public class PaymentOrchestrator {

    private static final List<PaymentMethod> CANONICAL_ORDER =
            List.of(PaymentMethod.CREDIT_CARD, PaymentMethod.Y_PAY, PaymentMethod.Y_POINT);

    private final Map<PaymentMethod, PaymentProcessor> processors = new EnumMap<>(PaymentMethod.class);

    public PaymentOrchestrator(List<PaymentProcessor> processorList) {
        for (PaymentProcessor processor : processorList) {
            processors.put(processor.method(), processor);
        }
    }

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
            if (amount - point <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION,
                        "외부 결제 수단이 부담할 금액이 없습니다.");
            }
        } else {
            if (point != amount) {
                throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION,
                        "포인트 단독 결제는 전액을 포인트로 부담해야 합니다.");
            }
        }

        if (methods.contains(PaymentMethod.CREDIT_CARD) && !StringUtils.hasText(command.context().cardNumber())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "카드번호가 필요합니다.");
        }
        if (methods.contains(PaymentMethod.Y_PAY) && !StringUtils.hasText(command.context().ypayToken())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Y페이 토큰이 필요합니다.");
        }
    }

    public PaymentExecution pay(PaymentCommand command) {
        validate(command);

        long amount = command.amount();
        long pointAmount = command.pointAmount();
        long externalAmount = amount - pointAmount;
        PaymentContext context = command.context();

        String pointTxId = null;
        if (pointAmount > 0) {
            ProcessorResult result = processor(PaymentMethod.Y_POINT).approve(context, pointAmount);
            if (!result.approved()) {
                return PaymentExecution.failed(result.failureCode(), result.failureReason());
            }
            pointTxId = result.transactionId();
        }

        String externalTxId = null;
        if (externalAmount > 0) {
            PaymentMethod externalMethod = externalMethod(command.methods());
            ProcessorResult result = processor(externalMethod).approve(context, externalAmount);
            if (!result.approved()) {
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
