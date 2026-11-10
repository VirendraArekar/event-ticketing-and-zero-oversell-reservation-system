package com.gatekeeper.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RedisDistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockService.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public RedisDistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<String> tryAcquire(String lockKey, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl);
            return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
        } catch (Exception ex) {
            log.warn("Failed to acquire Redis lock {}: {}", lockKey, ex.getMessage());
            return Optional.empty();
        }
    }

    public void release(String lockKey, String token) {
        if (token == null) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (Exception ex) {
            log.warn("Failed to release Redis lock {}: {}", lockKey, ex.getMessage());
        }
    }
}

