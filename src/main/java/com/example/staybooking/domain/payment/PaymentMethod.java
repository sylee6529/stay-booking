package com.example.staybooking.domain.payment;

/**
 * 결제 수단 (docs/06). CREDIT_CARD/Y_PAY는 외부 게이트웨이, Y_POINT는 자사 DB.
 */
public enum PaymentMethod {
    CREDIT_CARD,
    Y_PAY,
    Y_POINT;

    /** 외부 게이트웨이를 통하는 수단인지 (CREDIT_CARD, Y_PAY). */
    public boolean isExternal() {
        return this == CREDIT_CARD || this == Y_PAY;
    }
}
