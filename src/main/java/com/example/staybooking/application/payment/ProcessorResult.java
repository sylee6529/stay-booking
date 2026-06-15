package com.example.staybooking.application.payment;

import com.example.staybooking.api.error.ErrorCode;

/**
 * 단일 결제 수단의 승인 결과.
 */
public record ProcessorResult(boolean approved, String transactionId, ErrorCode failureCode, String failureReason) {

    public static ProcessorResult approved(String transactionId) {
        return new ProcessorResult(true, transactionId, null, null);
    }

    public static ProcessorResult declined(ErrorCode failureCode, String failureReason) {
        return new ProcessorResult(false, null, failureCode, failureReason);
    }
}
