package com.example.staybooking.application.payment;

import com.example.staybooking.application.error.ErrorCode;

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
