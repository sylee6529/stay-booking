-- Fixed-window user rate guard.
-- KEYS[1] = rate:booking:{userId}
-- KEYS[2] = rate:booking:{userId}:{idempotencyKey}
-- ARGV[1] = max requests
-- ARGV[2] = window seconds
--
-- return >= 0 : allowed, remaining requests in current window
-- return -N   : rejected, retry after N seconds

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 0
end

local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end

local max = tonumber(ARGV[1])
if current > max then
    local ttl = redis.call('TTL', KEYS[1])
    if ttl < 1 then
        ttl = tonumber(ARGV[2])
    end
    return -ttl
end

redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])
return max - current
