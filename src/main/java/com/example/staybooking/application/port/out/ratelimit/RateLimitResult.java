package com.example.staybooking.application.port.out.ratelimit;

public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

    public static RateLimitResult pass() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult block(long retryAfterSeconds) {
        return new RateLimitResult(false, Math.max(1, retryAfterSeconds));
    }
}
