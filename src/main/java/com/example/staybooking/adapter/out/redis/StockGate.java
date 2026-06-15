package com.example.staybooking.adapter.out.redis;

import com.example.staybooking.application.port.out.stock.AdmissionResult;
import com.example.staybooking.application.port.out.stock.StockGatePort;
import com.example.staybooking.application.port.out.stock.StockGateUnavailableException;
import com.example.staybooking.config.AppProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 재고 admission 게이트.
 *
 * <p>정합성의 최종 책임은 DB에 있다. Redis 장애/키 부재는 fail-closed로 거절한다.
 */
@Component
public class StockGate implements StockGatePort {

    private static final long KEY_MISSING = -2;
    private static final long SOLD_OUT = -1;
    private static final long DUPLICATE = -3;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> admissionScript;
    private final AppProperties properties;

    public StockGate(StringRedisTemplate redis,
                     @Qualifier("admissionScript") RedisScript<Long> admissionScript,
                     AppProperties properties) {
        this.redis = redis;
        this.admissionScript = admissionScript;
        this.properties = properties;
    }

    @Override
    public AdmissionResult admit(long productId, long userId, String idempotencyKey) {
        long ttlSeconds = Math.max(1, properties.getAdmissionTtl().toSeconds());
        Long raw;
        try {
            raw = redis.execute(
                    admissionScript,
                    List.of(stockKey(productId), admissionKey(userId, idempotencyKey)),
                    String.valueOf(ttlSeconds));
        } catch (DataAccessException e) {
            throw new StockGateUnavailableException("admission failed for product " + productId, e);
        }
        if (raw == null) {
            throw new StockGateUnavailableException("admission returned null for product " + productId);
        }
        long result = raw;
        if (result == KEY_MISSING) {
            throw new StockGateUnavailableException("stock key missing for product " + productId);
        }
        if (result == SOLD_OUT) {
            return AdmissionResult.soldOut();
        }
        if (result == DUPLICATE) {
            return AdmissionResult.duplicate();
        }
        return AdmissionResult.reserved(result);
    }

    /**
     * Redis 보상은 1회만 시도한다. 실패/불명확한 INCR 재시도는 초과 재고를 만들 수 있다.
     */
    @Override
    public boolean tryRelease(long productId) {
        try {
            redis.opsForValue().increment(stockKey(productId));
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    @Override
    public void overwriteStock(long productId, long quantity) {
        redis.opsForValue().set(stockKey(productId), Long.toString(quantity));
    }

    @Override
    public Long currentStock(long productId) {
        String value = redis.opsForValue().get(stockKey(productId));
        return value == null ? null : Long.parseLong(value);
    }

    private static String stockKey(long productId) {
        return "stock:" + productId;
    }

    private static String admissionKey(long userId, String idempotencyKey) {
        return "admission:" + userId + ":" + idempotencyKey;
    }
}
