package com.example.staybooking.api;

import com.example.staybooking.api.dto.CheckoutResponse;
import com.example.staybooking.application.CheckoutService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    /** GET /api/checkout?productId={id}&userId={id} — 주문서 조회 (재고 미차감). */
    @GetMapping("/checkout")
    public CheckoutResponse checkout(@RequestParam long productId, @RequestParam long userId) {
        return checkoutService.getCheckout(productId, userId);
    }
}
