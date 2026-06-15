package com.example.staybooking.application;

import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.application.port.out.stock.StockGatePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB available 수량을 Redis stock 키에 덮어쓴다. 진행 중 예약은 reserved 수량에 남는다.
 */
@Service
public class StockSyncService {

    private final PromotionProductRepository products;
    private final StockGatePort stockGate;

    public StockSyncService(PromotionProductRepository products, StockGatePort stockGate) {
        this.products = products;
        this.stockGate = stockGate;
    }

    @Transactional(readOnly = true)
    public long sync(long productId) {
        PromotionProduct product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("product not found: " + productId));
        long available = product.getAvailableQuantity();
        stockGate.overwriteStock(productId, available);
        return available;
    }

    @Transactional(readOnly = true)
    public void syncAll() {
        for (PromotionProduct product : products.findAll()) {
            stockGate.overwriteStock(product.getId(), product.getAvailableQuantity());
        }
    }
}
