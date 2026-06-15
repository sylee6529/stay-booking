package com.example.staybooking.application.payment.port;

public record GatewayResult(boolean approved, String transactionId, String reason) {

    public static GatewayResult approved(String transactionId) {
        return new GatewayResult(true, transactionId, null);
    }

    public static GatewayResult declined(String reason) {
        return new GatewayResult(false, null, reason);
    }
}
