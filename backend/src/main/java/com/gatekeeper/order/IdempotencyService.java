package com.gatekeeper.order;

import com.gatekeeper.common.exception.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String REDIS_KEY_PREFIX = "gatekeeper:idem:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public IdempotencyService(
            IdempotencyRecordRepository repository,
            StringRedisTemplate redisTemplate,
            JsonMapper jsonMapper
    ) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    private record CachedIdempotencyPayload(
            UUID userId,
            String requestHash,
            UUID orderId,
            OrderDtos.OrderResponse response
    ) {
    }

    public String hashRequest(UUID userId, OrderDtos.PlaceHoldRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(userId).append('|').append(request.eventId());
        request.items().stream()
                .sorted((a, b) -> a.ticketTypeId().toString().compareTo(b.ticketTypeId().toString()))
                .forEach(item -> sb.append('|').append(item.ticketTypeId()).append(':').append(item.quantity()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Optional<OrderDtos.OrderResponse> findExisting(String idempotencyKey, UUID userId, String requestHash) {
        try {
            String cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + idempotencyKey);
            if (cached != null) {
                CachedIdempotencyPayload payload = jsonMapper.readValue(cached, CachedIdempotencyPayload.class);
                validateOwnershipAndHash(payload.userId(), payload.requestHash(), userId, requestHash);
                return Optional.of(payload.response());
            }
        } catch (ConflictException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Redis idempotency read failed, falling back to MySQL: {}", ex.getMessage());
        }

        Optional<IdempotencyRecordEntity> dbRecord = repository.findByIdempotencyKey(idempotencyKey);
        if (dbRecord.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecordEntity rec = dbRecord.get();
        validateOwnershipAndHash(rec.getUserId(), rec.getRequestHash(), userId, requestHash);
        if (rec.getResponseBody() != null) {
            try {
                OrderDtos.OrderResponse response = jsonMapper.readValue(rec.getResponseBody(), OrderDtos.OrderResponse.class);
                cacheInRedis(idempotencyKey, userId, requestHash, response);
                return Optional.of(response);
            } catch (Exception ex) {
                log.warn("Failed to deserialize stored idempotency response for key={}", idempotencyKey);
            }
        }
        return Optional.empty();
    }

    public void saveInDb(
            String idempotencyKey,
            UUID userId,
            String requestHash,
            OrderDtos.OrderResponse response,
            Instant now
    ) {
        String serialized = jsonMapper.writeValueAsString(response);
        IdempotencyRecordEntity entity = new IdempotencyRecordEntity(
                UUID.randomUUID(),
                idempotencyKey,
                userId,
                requestHash,
                response.id(),
                201,
                serialized,
                now,
                now.plus(CACHE_TTL)
        );
        repository.saveAndFlush(entity);
    }

    public void cacheInRedis(
            String idempotencyKey,
            UUID userId,
            String requestHash,
            OrderDtos.OrderResponse response
    ) {
        try {
            CachedIdempotencyPayload payload = new CachedIdempotencyPayload(
                    userId, requestHash, response.id(), response);
            redisTemplate.opsForValue().set(
                    REDIS_KEY_PREFIX + idempotencyKey,
                    jsonMapper.writeValueAsString(payload),
                    CACHE_TTL
            );
        } catch (Exception ex) {
            log.warn("Redis idempotency cache write failed (MySQL fallback intact): {}", ex.getMessage());
        }
    }

    private void validateOwnershipAndHash(UUID storedUserId, String storedHash, UUID userId, String requestHash) {
        if (!storedUserId.equals(userId)) {
            throw new ConflictException("Idempotency-Key belongs to a different user");
        }
        if (!storedHash.equals(requestHash)) {
            throw new ConflictException("Idempotency-Key was already used with a different booking payload");
        }
    }
}

