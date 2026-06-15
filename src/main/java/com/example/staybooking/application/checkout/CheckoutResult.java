package com.example.staybooking.application.checkout;

import java.time.LocalDate;

public record CheckoutResult(
        long productId,
        String name,
        long price,
        LocalDate checkinDate,
        LocalDate checkoutDate,
        boolean open,
        long pointBalance
) {
}
