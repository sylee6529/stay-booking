package com.example.staybooking.application;

import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.infra.StockGate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB → Redis 단방향 재고 동기화 (docs/04). 재고의 진실은 DB {@code available_quantity}이며,
 * 이 값을 Redis {@code stock:} 키에 덮어쓴다(SET). 몇 번을 실행해도 안전(멱등).
 *
 * <p>실행 시점: 운영자 트리거(internal API), Redis 장애 복구 후, (데모 편의상) 앱 시작 시.
 * 진행 중 예약은 {@code reserved_quantity}에 있으므로 판매 중 sync해도 그 수량이 다시 열리지 않는다.
 */
@Service
public class StockSyncService {

    private final PromotionProductRepository products;
    private final StockGate stockGate;

    public StockSyncService(PromotionProductRepository products, StockGate stockGate) {
        this.products = products;
        this.stockGate = stockGate;
    }

    /**
     * 단일 상품 재고 동기화.
     *
     * @return Redis에 덮어쓴 available 수량
     */
    @Transactional(readOnly = true)
    public long sync(long productId) {
        PromotionProduct product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("product not found: " + productId));
        long available = product.getAvailableQuantity();
        stockGate.overwriteStock(productId, available);
        return available;
    }

    /** 전 상품 재고 동기화 (앱 시작/복구 절차용). */
    @Transactional(readOnly = true)
    public void syncAll() {
        for (PromotionProduct product : products.findAll()) {
            stockGate.overwriteStock(product.getId(), product.getAvailableQuantity());
        }
    }
}
