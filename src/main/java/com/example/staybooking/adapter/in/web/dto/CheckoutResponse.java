package com.example.staybooking.adapter.in.web.dto;

import java.time.LocalDate;

/**
 * 주문서 조회 응답 (docs/10). 재고는 차감하지 않으며 남은 수량도 노출하지 않는다(정책).
 */
public record CheckoutResponse(
        long productId,
        String name,
        long price,
        LocalDate checkinDate,
        LocalDate checkoutDate,
        boolean open,
        long pointBalance
) {
}
