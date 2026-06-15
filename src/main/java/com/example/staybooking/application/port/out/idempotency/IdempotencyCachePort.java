package com.example.staybooking.application.port.out.idempotency;

import java.util.Optional;

public interface IdempotencyCachePort {

    Optional<CachedIdempotencyResponse> findCachedResponse(long userId, String idempotencyKey, String requestHash);

    void cacheResponse(long userId, String idempotencyKey, String requestHash, int httpStatus, String responseBody);
}
