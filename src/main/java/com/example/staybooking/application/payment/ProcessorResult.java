package com.example.staybooking.application.payment;

import com.example.staybooking.application.error.ErrorCode;

public record ProcessorResult(boolean approved, String transactionId, ErrorCode failureCode, String failureReason) {

    public static ProcessorResult approved(String transactionId) {
        return new ProcessorResult(true, transactionId, null, null);
    }

    public static ProcessorResult declined(ErrorCode failureCode, String failureReason) {
        return new ProcessorResult(false, null, failureCode, failureReason);
    }
}
