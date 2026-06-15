package com.example.staybooking.application.payment;

import com.example.staybooking.domain.payment.PaymentMethod;

import java.util.List;

/**
 * 결제 실행 명령. amount 는 서버가 계산한 상품 가격이며, pointAmount 만 클라이언트가 지정한다 (docs/01 A4·A5).
 */
public record PaymentCommand(List<PaymentMethod> methods, long amount, long pointAmount, PaymentContext context) {
}
