package com.example.staybooking.adapter.out.redis;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 멱등성 가속 캐시 (docs/05). 진실은 DB UNIQUE(user_id, idempotency_key)이고, 이 캐시는 성능 최적화일 뿐이다.
 *
 * <p>따라서 모든 연산은 best-effort다. Redis 실패는 무시하고 진행해도 DB unique가 방어한다
 * (admission Lua 실패와 달리 Fail-Closed 대상이 아니다 — docs/05, docs/07).
 */
@Component
public class IdempotencyGate {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public IdempotencyGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 완료 응답 캐시 조회. 캐시 미스/Redis 실패면 {@link Optional#empty()} → 호출자는 DB로 폴백. */
    public Optional<String> findCachedResponse(long userId, String idempotencyKey) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(idemKey(userId, idempotencyKey)));
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    /** 완료 응답 캐시 기록 (TTL 24h, best-effort). Redis 실패는 삼킨다. */
    public void cacheResponse(long userId, String idempotencyKey, String responseBody) {
        try {
            redis.opsForValue().set(idemKey(userId, idempotencyKey), responseBody, TTL);
        } catch (DataAccessException ignored) {
            // 캐시는 성능용. 실패해도 DB가 진실이므로 무시한다.
        }
    }

    private static String idemKey(long userId, String idempotencyKey) {
        return "idem:" + userId + ":" + idempotencyKey;
    }
}
