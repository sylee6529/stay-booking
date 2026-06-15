package com.example.staybooking.adapter.in.web;

import com.example.staybooking.application.StockSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal")
public class InternalController {

    private final StockSyncService stockSyncService;

    public InternalController(StockSyncService stockSyncService) {
        this.stockSyncService = stockSyncService;
    }

    @PostMapping("/products/{productId}/stock-sync")
    public ResponseEntity<Map<String, Object>> syncStock(@PathVariable long productId) {
        stockSyncService.sync(productId);
        return ResponseEntity.ok(Map.of("productId", productId, "status", "SYNCED"));
    }
}
