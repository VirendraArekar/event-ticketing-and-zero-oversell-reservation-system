package com.gatekeeper.redis;

import com.gatekeeper.common.exception.ForbiddenException;
import com.gatekeeper.common.exception.NotFoundException;
import com.gatekeeper.common.exception.WaitingRoomRequiredException;
import com.gatekeeper.event.EventEntity;
import com.gatekeeper.event.EventRepository;
import com.gatekeeper.event.EventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class WaitingRoomService {

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomService.class);
    private static final String QUEUE_KEY_PREFIX = "gatekeeper:wr:queue:";
    private static final String ACTIVE_KEY_PREFIX = "gatekeeper:wr:active:";
    private static final String SEQ_KEY_PREFIX = "gatekeeper:wr:seq:";
    private static final String TOKEN_KEY_PREFIX = "gatekeeper:wr:token:";
    private static final String SPIKE_KEY_PREFIX = "gatekeeper:wr:spike:";

    private final StringRedisTemplate redisTemplate;
    private final EventRepository eventRepository;
    private final long autoSpikeThreshold;
    private final long slotTtlSeconds;

    public WaitingRoomService(
            StringRedisTemplate redisTemplate,
            EventRepository eventRepository,
            @Value("${gatekeeper.waiting-room.auto-spike-threshold:1000}") long autoSpikeThreshold,
            @Value("${gatekeeper.waiting-room.slot-ttl-seconds:300}") long slotTtlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.eventRepository = eventRepository;
        this.autoSpikeThreshold = autoSpikeThreshold;
        this.slotTtlSeconds = slotTtlSeconds;
    }

    public enum WaitingRoomState {
        ADMITTED,
        QUEUED
    }

    public record WaitingRoomStatusResponse(
            UUID eventId,
            WaitingRoomState status,
            String queueToken,
            long position,
            long estimatedWaitSeconds,
            int pollAfterSeconds
    ) {
    }

    public WaitingRoomStatusResponse enter(UUID eventId, UUID userId) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new NotFoundException("Published event not found");
        }

        String token = UUID.randomUUID().toString();
        long nowMs = System.currentTimeMillis();
        String activeKey = ACTIVE_KEY_PREFIX + eventId;
        String queueKey = QUEUE_KEY_PREFIX + eventId;
        String tokenKey = TOKEN_KEY_PREFIX + token;

        try {
            redisTemplate.opsForValue().set(tokenKey, eventId + "|" + userId, Duration.ofMinutes(30));
            redisTemplate.opsForZSet().removeRangeByScore(activeKey, 0, nowMs);

            Long activeCount = redisTemplate.opsForZSet().zCard(activeKey);
            Long queueCount = redisTemplate.opsForZSet().zCard(queueKey);
            int maxSlots = Math.max(1, event.getWaitingRoomThreshold());

            if ((activeCount == null || activeCount < maxSlots) && (queueCount == null || queueCount == 0)) {
                redisTemplate.opsForZSet().add(activeKey, token, nowMs + (slotTtlSeconds * 1000L));
                redisTemplate.expire(activeKey, Duration.ofSeconds(slotTtlSeconds + 60));
                return new WaitingRoomStatusResponse(eventId, WaitingRoomState.ADMITTED, token, 0L, 0L, 0);
            }

            Long seq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + eventId);
            long score = seq != null ? seq : nowMs;
            redisTemplate.opsForZSet().add(queueKey, token, score);
            redisTemplate.expire(queueKey, Duration.ofHours(2));
            Long rank = redisTemplate.opsForZSet().rank(queueKey, token);
            long position = rank != null ? rank + 1 : 1L;
            return new WaitingRoomStatusResponse(
                    eventId,
                    WaitingRoomState.QUEUED,
                    token,
                    position,
                    position * 2L,
                    2
            );
        } catch (Exception ex) {
            log.warn("Waiting room Redis failure, admitting directly: {}", ex.getMessage());
            return new WaitingRoomStatusResponse(eventId, WaitingRoomState.ADMITTED, token, 0L, 0L, 0);
        }
    }

    public WaitingRoomStatusResponse getStatus(UUID eventId, UUID userId, String token) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        long nowMs = System.currentTimeMillis();
        String activeKey = ACTIVE_KEY_PREFIX + eventId;
        String queueKey = QUEUE_KEY_PREFIX + eventId;
        String tokenKey = TOKEN_KEY_PREFIX + token;

        String metadata = redisTemplate.opsForValue().get(tokenKey);
        if (metadata == null || !metadata.equals(eventId + "|" + userId)) {
            throw new ForbiddenException("Invalid or expired waiting room token");
        }

        Double activeScore = redisTemplate.opsForZSet().score(activeKey, token);
        if (activeScore != null && activeScore > nowMs) {
            return new WaitingRoomStatusResponse(eventId, WaitingRoomState.ADMITTED, token, 0L, 0L, 0);
        }

        redisTemplate.opsForZSet().removeRangeByScore(activeKey, 0, nowMs);
        Long activeCount = redisTemplate.opsForZSet().zCard(activeKey);
        long active = activeCount != null ? activeCount : 0L;
        int maxSlots = Math.max(1, event.getWaitingRoomThreshold());

        Long rank = redisTemplate.opsForZSet().rank(queueKey, token);
        if (rank == null) {
            throw new NotFoundException("Waiting room queue entry not found or expired");
        }

        long availableSlots = maxSlots - active;
        if (availableSlots > 0 && rank < availableSlots) {
            redisTemplate.opsForZSet().remove(queueKey, token);
            redisTemplate.opsForZSet().add(activeKey, token, nowMs + (slotTtlSeconds * 1000L));
            redisTemplate.expire(activeKey, Duration.ofSeconds(slotTtlSeconds + 60));
            return new WaitingRoomStatusResponse(eventId, WaitingRoomState.ADMITTED, token, 0L, 0L, 0);
        }

        long position = rank + 1L;
        return new WaitingRoomStatusResponse(
                eventId,
                WaitingRoomState.QUEUED,
                token,
                position,
                position * 2L,
                2
        );
    }

    public void validateAdmissionForBooking(UUID eventId, UUID userId, String providedToken) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));

        boolean spikeActive = recordAndCheckSpike(eventId);
        if (!event.isWaitingRoomEnabled() && !spikeActive) {
            return;
        }

        if (providedToken == null || providedToken.isBlank()) {
            WaitingRoomStatusResponse entry = enter(eventId, userId);
            if (entry.status() == WaitingRoomState.ADMITTED) {
                consumeSlot(eventId, entry.queueToken());
                return;
            }
            throw new WaitingRoomRequiredException(
                    "Virtual waiting room active; please wait for your queue turn",
                    eventId,
                    entry.queueToken(),
                    entry.position()
            );
        }

        WaitingRoomStatusResponse status = getStatus(eventId, userId, providedToken.trim());
        if (status.status() != WaitingRoomState.ADMITTED) {
            throw new WaitingRoomRequiredException(
                    "Still in virtual waiting room queue at position " + status.position(),
                    eventId,
                    status.queueToken(),
                    status.position()
            );
        }
        consumeSlot(eventId, providedToken.trim());
    }

    public void consumeSlot(UUID eventId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(ACTIVE_KEY_PREFIX + eventId, token);
            redisTemplate.delete(TOKEN_KEY_PREFIX + token);
        } catch (Exception ex) {
            log.warn("Failed to release waiting room slot for event={}: {}", eventId, ex.getMessage());
        }
    }

    private boolean recordAndCheckSpike(UUID eventId) {
        try {
            String key = SPIKE_KEY_PREFIX + eventId;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(10));
            }
            return count != null && count > autoSpikeThreshold;
        } catch (Exception ex) {
            return false;
        }
    }
}

