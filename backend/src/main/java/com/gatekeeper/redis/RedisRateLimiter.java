package com.gatekeeper.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local ttl_sec = math.max(10, math.ceil(capacity / math.max(refill_rate, 0.001)) * 2)

            local data = redis.call('HMGET', key, 'tokens', 'last_ms')
            local tokens = tonumber(data[1])
            local last_ms = tonumber(data[2])

            if tokens == nil then
                tokens = capacity
                last_ms = now_ms
            else
                local elapsed = math.max(0, now_ms - last_ms) / 1000.0
                tokens = math.min(capacity, tokens + (elapsed * refill_rate))
                last_ms = now_ms
            end

            local allowed = 0
            local retry_after = 0
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            else
                local deficit = requested - tokens
                retry_after = math.ceil(deficit / math.max(refill_rate, 0.001))
            end

            redis.call('HSET', key, 'tokens', tostring(tokens), 'last_ms', tostring(last_ms))
            redis.call('EXPIRE', key, ttl_sec)

            local reset_sec = math.ceil((capacity - tokens) / math.max(refill_rate, 0.001))
            return {allowed, math.floor(tokens), capacity, retry_after, reset_sec}
            """,
            List.class
    );

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public record RateLimitResult(
            boolean allowed,
            long remaining,
            long limit,
            long retryAfterSeconds,
            long resetEpochSeconds
    ) {
    }

    public RateLimitResult consume(String bucketKey, long capacity, double refillPerSecond) {
        long nowMs = System.currentTimeMillis();
        try {
            List<?> res = redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(bucketKey),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond),
                    String.valueOf(nowMs),
                    "1"
            );
            if (res == null || res.size() < 5) {
                return new RateLimitResult(true, capacity, capacity, 0L, (nowMs / 1000L) + 1);
            }
            long allowed = toLong(res.get(0));
            long remaining = toLong(res.get(1));
            long limit = toLong(res.get(2));
            long retryAfter = toLong(res.get(3));
            long resetSec = toLong(res.get(4));
            return new RateLimitResult(
                    allowed == 1L,
                    Math.max(0L, remaining),
                    limit,
                    Math.max(1L, retryAfter),
                    (nowMs / 1000L) + Math.max(1L, resetSec)
            );
        } catch (Exception ex) {
            log.warn("Redis rate limiter unavailable for key={}, allowing request: {}", bucketKey, ex.getMessage());
            return new RateLimitResult(true, capacity, capacity, 0L, (nowMs / 1000L) + 1);
        }
    }

    private static long toLong(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(val));
    }
}

