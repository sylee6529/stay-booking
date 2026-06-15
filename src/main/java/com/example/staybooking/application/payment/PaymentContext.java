package com.example.staybooking.application.payment;

/**
 * 결제 실행에 필요한 컨텍스트. 명시적 객체로 전달한다(MDC/ThreadLocal 미사용, docs/08).
 */
public record PaymentContext(long userId, long bookingRequestId, String cardNumber, String ypayToken) {
}
