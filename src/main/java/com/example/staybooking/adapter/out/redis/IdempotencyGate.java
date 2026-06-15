package com.example.staybooking.adapter.out.redis;

import com.example.staybooking.application.port.out.idempotency.CachedIdempotencyResponse;
import com.example.staybooking.application.port.out.idempotency.IdempotencyCachePort;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 멱등 응답 캐시. 진실은 DB UNIQUE(user_id, idempotency_key)이고 Redis는 best-effort다.
 */
@Component
public class IdempotencyGate implements IdempotencyCachePort {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String DELIMITER = "\n";

    private final StringRedisTemplate redis;

    public IdempotencyGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<CachedIdempotencyResponse> findCachedResponse(long userId, String idempotencyKey,
                                                                  String requestHash) {
        try {
            String raw = redis.opsForValue().get(idemKey(userId, idempotencyKey));
            if (raw == null) {
                return Optional.empty();
            }
            String[] parts = raw.split(DELIMITER, 3);
            if (parts.length != 3 || !parts[0].equals(requestHash)) {
                return Optional.empty();
            }
            return Optional.of(new CachedIdempotencyResponse(Integer.parseInt(parts[1]), parts[2]));
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void cacheResponse(long userId, String idempotencyKey, String requestHash,
                              int httpStatus, String responseBody) {
        try {
            String value = requestHash + DELIMITER + httpStatus + DELIMITER + responseBody;
            redis.opsForValue().set(idemKey(userId, idempotencyKey), value, TTL);
        } catch (DataAccessException ignored) {
        }
    }

    private static String idemKey(long userId, String idempotencyKey) {
        return "idem:" + userId + ":" + idempotencyKey;
    }
}
