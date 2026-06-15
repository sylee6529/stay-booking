package com.example.staybooking.application.stock;

public interface StockGatePort {

    AdmissionResult admit(long productId, long userId, String idempotencyKey);

    boolean tryRelease(long productId);

    void overwriteStock(long productId, long quantity);

    Long currentStock(long productId);
}
