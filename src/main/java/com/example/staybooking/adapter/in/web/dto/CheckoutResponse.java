package com.example.staybooking.adapter.in.web.dto;

import java.time.LocalDate;

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
