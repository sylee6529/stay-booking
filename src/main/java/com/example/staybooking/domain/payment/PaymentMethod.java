package com.example.staybooking.domain.payment;

public enum PaymentMethod {
    CREDIT_CARD,
    Y_PAY,
    Y_POINT;

    public boolean isExternal() {
        return this == CREDIT_CARD || this == Y_PAY;
    }
}
