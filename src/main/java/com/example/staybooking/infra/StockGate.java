package com.example.staybooking.infra;

import com.example.staybooking.application.stock.AdmissionResult;
import com.example.staybooking.application.stock.StockGatePort;
import com.example.staybooking.application.stock.StockGateUnavailableException;
import com.example.staybooking.config.AppProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 재고 게이트 — "990개를 빠르게 거절하는 부하 차단기" (docs/02, docs/04).
 *
 * <p>정합성의 최종 책임은 DB에 있다. 이 게이트는 admission(원자 선점)과 best-effort 보상만 담당한다.
 * Redis 장애/키 부재는 {@link StockGateUnavailableException}로 Fail-Closed 한다 (불변식 #6).
 */
@Component
public class StockGate implements StockGatePort {

    private static final long KEY_MISSING = -2;
    private static final long SOLD_OUT = -1;
    private static final long DUPLICATE = -3;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> admissionScript;
    private final AppProperties properties;

    public StockGate(StringRedisTemplate redis, RedisScript<Long> admissionScript, AppProperties properties) {
        this.redis = redis;
        this.admissionScript = admissionScript;
        this.properties = properties;
    }

    /**
     * 원자 admission: 중복 확인 + check-and-decrement (docs/04 Lua).
     *
     * @throws StockGateUnavailableException Redis 장애/타임아웃 또는 stock 키 부재 (Fail-Closed)
     */
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
            // 연결 실패/타임아웃 등 인프라 예외 → Fail-Closed
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
     * 보상 시 재고 복구. {@code INCR}은 best-effort 1회만 시도한다 — 실패/불명확하면 호출자가
     * NEEDS_SYNC로 남기고 DB 기준 stock sync가 회복한다. 중복 INCR은 phantom stock(초과판매 방향)이므로 금지 (docs/06).
     *
     * @return true = INCR 성공, false = 실패(불명확). 절대 재시도하지 않는다.
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

    /** DB → Redis 단방향 재고 덮어쓰기 (멱등). StockSyncService 전용 (docs/04). */
    @Override
    public void overwriteStock(long productId, long quantity) {
        redis.opsForValue().set(stockKey(productId), Long.toString(quantity));
    }

    /** 현재 Redis 재고(근사치). 키 부재면 null. */
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
