-- 재고 admission: 중복 확인 + check-and-decrement 를 Redis 단일 스레드에서 원자 실행한다 (docs/04).
-- KEYS[1] = stock:{productId}
-- KEYS[2] = admission:{userId}:{idempotencyKey}
-- ARGV[1] = admission ttl seconds
--
-- 반환값:
--   >= 0 : 선점 성공, 남은 재고 수량
--   -1   : 매진 (stock <= 0)
--   -2   : stock 키 부재 → Fail-Closed (키 없음을 '무한 재고'로 오해하지 않는다)
--   -3   : 같은 (userId, idempotencyKey) admission 중복

if redis.call('EXISTS', KEYS[2]) == 1 then
    return -3
end

local stock = redis.call('GET', KEYS[1])
if stock == false then
    return -2
end
if tonumber(stock) <= 0 then
    return -1
end

redis.call('DECR', KEYS[1])
redis.call('SET', KEYS[2], '1', 'EX', ARGV[1])
return tonumber(stock) - 1
