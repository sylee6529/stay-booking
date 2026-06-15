package com.example.staybooking.adapter.out.redis;

import com.example.staybooking.application.port.out.ratelimit.RateLimitResult;
import com.example.staybooking.application.port.out.ratelimit.UserRateLimiterPort;
import com.example.staybooking.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisUserRateLimiter implements UserRateLimiterPort {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> rateLimitScript;
    private final AppProperties properties;

    public RedisUserRateLimiter(StringRedisTemplate redis,
                                @Qualifier("rateLimitScript") RedisScript<Long> rateLimitScript,
                                AppProperties properties) {
        this.redis = redis;
        this.rateLimitScript = rateLimitScript;
        this.properties = properties;
    }

    @Override
    public RateLimitResult acquire(long userId, String idempotencyKey) {
        AppProperties.UserRateLimit config = properties.getUserRateLimit();
        long windowSeconds = Math.max(1, config.getWindow().toSeconds());
        try {
            Long raw = redis.execute(
                    rateLimitScript,
                    List.of(rateKey(userId), idempotencyKeyKey(userId, idempotencyKey)),
                    Integer.toString(config.getMaxRequests()),
                    Long.toString(windowSeconds));
            if (raw == null || raw >= 0) {
                return RateLimitResult.pass();
            }
            return RateLimitResult.block(Math.abs(raw));
        } catch (DataAccessException e) {
            return RateLimitResult.pass();
        }
    }

    private static String rateKey(long userId) {
        return "rate:booking:" + userId;
    }

    private static String idempotencyKeyKey(long userId, String idempotencyKey) {
        return "rate:booking:" + userId + ":" + idempotencyKey;
    }
}
