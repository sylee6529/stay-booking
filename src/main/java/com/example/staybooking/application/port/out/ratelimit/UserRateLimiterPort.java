package com.example.staybooking.application.port.out.ratelimit;

public interface UserRateLimiterPort {

    RateLimitResult acquire(long userId, String idempotencyKey);
}
