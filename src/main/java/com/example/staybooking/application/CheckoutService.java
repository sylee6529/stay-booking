package com.example.staybooking.application;

import com.example.staybooking.application.checkout.CheckoutResult;
import com.example.staybooking.application.error.BusinessException;
import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.domain.point.UserPointsRepository;
import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CheckoutService {

    private final PromotionProductRepository products;
    private final UserPointsRepository userPoints;

    public CheckoutService(PromotionProductRepository products, UserPointsRepository userPoints) {
        this.products = products;
        this.userPoints = userPoints;
    }

    @Transactional(readOnly = true)
    public CheckoutResult getCheckout(long productId, long userId) {
        PromotionProduct product = products.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        long pointBalance = userPoints.findByUserId(userId)
                .map(p -> p.getBalance())
                .orElse(0L);

        return new CheckoutResult(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getCheckinDate(),
                product.getCheckoutDate(),
                product.isOpen(LocalDateTime.now()),
                pointBalance);
    }
}
