package com.example.staybooking.application.port.out.idempotency;

public record CachedIdempotencyResponse(int httpStatus, String responseBody) {
}
