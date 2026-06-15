package com.example.staybooking.adapter.in.web;

import com.example.staybooking.adapter.in.web.dto.CheckoutResponse;
import com.example.staybooking.application.CheckoutService;
import com.example.staybooking.application.checkout.CheckoutResult;
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

    @GetMapping("/checkout")
    public CheckoutResponse checkout(@RequestParam long productId, @RequestParam long userId) {
        CheckoutResult result = checkoutService.getCheckout(productId, userId);
        return new CheckoutResponse(
                result.productId(),
                result.name(),
                result.price(),
                result.checkinDate(),
                result.checkoutDate(),
                result.open(),
                result.pointBalance());
    }
}
