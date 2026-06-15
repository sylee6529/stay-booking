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

/**
 * 주문서(Checkout) 조회 (docs/10). 상품 정보 + 프로모션 오픈 여부 + 사용자 포인트 잔액을 반환한다.
 *
 * <p>읽기 전용이며 재고를 차감하지 않는다 (불변식 #4의 전제: Checkout 진입 시 재고 미차감).
 */
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

        // 포인트 미사용 사용자(행 없음)는 0으로 응답한다 (docs/10).
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
