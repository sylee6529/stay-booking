package com.example.staybooking.application.payment;

import com.example.staybooking.api.error.ErrorCode;

/**
 * 결제 실행 결과. 성공 시 {@code payments} 행 기록에 필요한 값(거래 식별자, 금액 분배, 수단 라벨)을 담는다.
 *
 * <p>실패 시 orchestrator는 자신이 만든 부분 효과를 보상한 뒤(포인트 환불) 이 결과를 돌려준다 —
 * 즉 success=false 이면 "결제로 인해 차감된 돈이 없는" 상태가 보장된다.
 */
public record PaymentExecution(
        boolean success,
        ErrorCode failureCode,
        String failureReason,
        String transactionId,
        String methodsLabel,
        long totalAmount,
        long pointAmount,
        long externalAmount
) {

    public static PaymentExecution success(String transactionId, String methodsLabel,
                                           long totalAmount, long pointAmount, long externalAmount) {
        return new PaymentExecution(true, null, null, transactionId, methodsLabel,
                totalAmount, pointAmount, externalAmount);
    }

    public static PaymentExecution failed(ErrorCode failureCode, String failureReason) {
        return new PaymentExecution(false, failureCode, failureReason, null, null, 0, 0, 0);
    }
}
