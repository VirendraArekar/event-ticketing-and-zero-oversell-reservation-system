package com.gatekeeper.redis;

import com.gatekeeper.common.web.PageResponse;
import com.gatekeeper.event.EventDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class EventCacheService {

    private static final Logger log = LoggerFactory.getLogger(EventCacheService.class);
    private static final String DETAIL_KEY_PREFIX = "gatekeeper:cache:event:detail:";
    private static final String LIST_VERSION_KEY = "gatekeeper:cache:events:version";
    private static final String LIST_KEY_PREFIX = "gatekeeper:cache:events:list:";
    private static final Duration DETAIL_TTL = Duration.ofSeconds(60);
    private static final Duration LIST_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public EventCacheService(StringRedisTemplate redisTemplate, JsonMapper jsonMapper) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    public EventDtos.EventDetailResponse getOrLoadEventDetail(
            UUID eventId,
            Supplier<EventDtos.EventDetailResponse> loader
    ) {
        String key = DETAIL_KEY_PREFIX + eventId;
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw != null) {
                return jsonMapper.readValue(raw, EventDtos.EventDetailResponse.class);
            }
        } catch (Exception ex) {
            log.warn("Event detail cache read failed for {}: {}", eventId, ex.getMessage());
        }

        EventDtos.EventDetailResponse fresh = loader.get();
        try {
            redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(fresh), DETAIL_TTL);
        } catch (Exception ex) {
            log.warn("Event detail cache write failed for {}: {}", eventId, ex.getMessage());
        }
        return fresh;
    }

    public PageResponse<EventDtos.EventSummaryResponse> getOrLoadEventList(
            String search,
            String category,
            Instant from,
            Instant to,
            int page,
            int size,
            Supplier<PageResponse<EventDtos.EventSummaryResponse>> loader
    ) {
        Optional<String> listKeyOpt = buildListCacheKey(search, category, from, to, page, size);
        if (listKeyOpt.isPresent()) {
            try {
                String raw = redisTemplate.opsForValue().get(listKeyOpt.get());
                if (raw != null) {
                    return jsonMapper.readValue(raw, new TypeReference<PageResponse<EventDtos.EventSummaryResponse>>() {
                    });
                }
            } catch (Exception ex) {
                log.warn("Event list cache read failed: {}", ex.getMessage());
            }
        }

        PageResponse<EventDtos.EventSummaryResponse> fresh = loader.get();
        if (listKeyOpt.isPresent()) {
            try {
                redisTemplate.opsForValue().set(listKeyOpt.get(), jsonMapper.writeValueAsString(fresh), LIST_TTL);
            } catch (Exception ex) {
                log.warn("Event list cache write failed: {}", ex.getMessage());
            }
        }
        return fresh;
    }

    public void evictEvent(UUID eventId) {
        try {
            redisTemplate.delete(DETAIL_KEY_PREFIX + eventId);
            redisTemplate.opsForValue().increment(LIST_VERSION_KEY);
        } catch (Exception ex) {
            log.warn("Event cache eviction failed for {}: {}", eventId, ex.getMessage());
        }
    }

    public void evictListingsOnly() {
        try {
            redisTemplate.opsForValue().increment(LIST_VERSION_KEY);
        } catch (Exception ex) {
            log.warn("Event listing cache version bump failed: {}", ex.getMessage());
        }
    }

    private Optional<String> buildListCacheKey(
            String search,
            String category,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        try {
            String version = redisTemplate.opsForValue().get(LIST_VERSION_KEY);
            if (version == null) {
                version = "0";
            }
            String rawQuery = (search == null ? "" : search.trim()) + "|"
                    + (category == null ? "" : category.trim()) + "|"
                    + (from == null ? "" : from.toString()) + "|"
                    + (to == null ? "" : to.toString()) + "|"
                    + page + "|" + size;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawQuery.getBytes(StandardCharsets.UTF_8));
            return Optional.of(LIST_KEY_PREFIX + "v" + version + ":" + HexFormat.of().formatHex(digest));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}

