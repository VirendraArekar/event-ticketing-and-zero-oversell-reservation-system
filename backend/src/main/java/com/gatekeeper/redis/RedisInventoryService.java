package com.gatekeeper.redis;

import com.gatekeeper.event.TicketInventoryEntity;
import com.gatekeeper.event.TicketInventoryRepository;
import com.gatekeeper.order.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RedisInventoryService {

    private static final Logger log = LoggerFactory.getLogger(RedisInventoryService.class);
    private static final String AVAIL_KEY_PREFIX = "gatekeeper:inv:avail:";
    private static final String HOLD_KEY_PREFIX = "gatekeeper:inv:hold:";
    private static final Duration AVAIL_TTL = Duration.ofHours(24);

    /**
     * KEYS[1..N] = available seat counter keys (sorted by ticketTypeId)
     * ARGV[1..N] = requested quantities
     * Returns:
     *   {1, 0, 0}       -> all reserved atomically in Redis
     *   {-2, idx, 0}    -> cache miss on key idx (caller should warm from MySQL)
     *   {0, idx, avail} -> insufficient seats on key idx (no keys mutated)
     */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RESERVE_LUA = new DefaultRedisScript<>(
            """
            local n = #KEYS
            for i = 1, n do
                local raw = redis.call('GET', KEYS[i])
                if not raw then
                    return {-2, i, 0}
                end
                local avail = tonumber(raw)
                local req = tonumber(ARGV[i])
                if avail < req then
                    return {0, i, avail}
                end
            end
            for i = 1, n do
                redis.call('DECRBY', KEYS[i], tonumber(ARGV[i]))
            end
            return {1, 0, 0}
            """,
            List.class
    );

    private final StringRedisTemplate redisTemplate;
    private final TicketInventoryRepository ticketInventoryRepository;
    private final long holdDurationSeconds;

    public RedisInventoryService(
            StringRedisTemplate redisTemplate,
            TicketInventoryRepository ticketInventoryRepository,
            @Value("${gatekeeper.booking.hold-duration-seconds:600}") long holdDurationSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.ticketInventoryRepository = ticketInventoryRepository;
        this.holdDurationSeconds = holdDurationSeconds;
    }

    public enum FrontDoorOutcome {
        RESERVED,
        REJECTED_SOLD_OUT,
        BYPASS_TO_MYSQL
    }

    public record FrontDoorResult(
            FrontDoorOutcome outcome,
            UUID failedTicketTypeId,
            int availableCount
    ) {
    }

    public FrontDoorResult tryReserveFrontDoor(List<InventoryService.SeatRequest> sortedRequests) {
        List<String> keys = sortedRequests.stream()
                .map(r -> AVAIL_KEY_PREFIX + r.ticketTypeId())
                .toList();
        String[] args = sortedRequests.stream()
                .map(r -> String.valueOf(r.quantity()))
                .toArray(String[]::new);

        try {
            List<?> result = redisTemplate.execute(RESERVE_LUA, keys, (Object[]) args);
            if (result == null || result.isEmpty()) {
                return new FrontDoorResult(FrontDoorOutcome.BYPASS_TO_MYSQL, null, 0);
            }
            long status = toLong(result.get(0));
            if (status == 1L) {
                return new FrontDoorResult(FrontDoorOutcome.RESERVED, null, 0);
            }
            if (status == -2L) {
                // Warm cache lazily from MySQL and retry Lua once
                warmMissingCounters(sortedRequests);
                result = redisTemplate.execute(RESERVE_LUA, keys, (Object[]) args);
                if (result != null && !result.isEmpty()) {
                    status = toLong(result.get(0));
                    if (status == 1L) {
                        return new FrontDoorResult(FrontDoorOutcome.RESERVED, null, 0);
                    }
                    if (status == 0L) {
                        int idx = (int) toLong(result.get(1)) - 1;
                        int avail = (int) toLong(result.get(2));
                        return new FrontDoorResult(
                                FrontDoorOutcome.REJECTED_SOLD_OUT,
                                sortedRequests.get(idx).ticketTypeId(),
                                Math.max(0, avail)
                        );
                    }
                }
                return new FrontDoorResult(FrontDoorOutcome.BYPASS_TO_MYSQL, null, 0);
            }
            if (status == 0L) {
                int idx = (int) toLong(result.get(1)) - 1;
                int avail = (int) toLong(result.get(2));
                return new FrontDoorResult(
                        FrontDoorOutcome.REJECTED_SOLD_OUT,
                        sortedRequests.get(idx).ticketTypeId(),
                        Math.max(0, avail)
                );
            }
            return new FrontDoorResult(FrontDoorOutcome.BYPASS_TO_MYSQL, null, 0);
        } catch (Exception ex) {
            log.warn("Redis inventory front-door unavailable, falling back directly to MySQL: {}", ex.getMessage());
            return new FrontDoorResult(FrontDoorOutcome.BYPASS_TO_MYSQL, null, 0);
        }
    }

    public void recordHoldMarker(UUID orderId, List<InventoryService.SeatRequest> items) {
        try {
            StringBuilder sb = new StringBuilder();
            for (InventoryService.SeatRequest item : items) {
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(item.ticketTypeId()).append(':').append(item.quantity());
            }
            redisTemplate.opsForValue().set(
                    HOLD_KEY_PREFIX + orderId,
                    sb.toString(),
                    Duration.ofSeconds(holdDurationSeconds + 60)
            );
        } catch (Exception ex) {
            log.warn("Failed to record Redis hold marker for order={}: {}", orderId, ex.getMessage());
        }
    }

    public void clearHoldMarker(UUID orderId) {
        try {
            redisTemplate.delete(HOLD_KEY_PREFIX + orderId);
        } catch (Exception ex) {
            log.warn("Failed to clear Redis hold marker for order={}: {}", orderId, ex.getMessage());
        }
    }

    public void rollbackFrontDoorReservation(List<InventoryService.SeatRequest> sortedRequests) {
        for (InventoryService.SeatRequest req : sortedRequests) {
            syncTicketTypeFromMySql(req.ticketTypeId());
        }
    }

    public void releaseSeatsInRedis(List<InventoryService.SeatRequest> requests) {
        for (InventoryService.SeatRequest req : requests) {
            syncTicketTypeFromMySql(req.ticketTypeId());
        }
    }

    public void syncTicketTypeFromMySql(UUID ticketTypeId) {
        try {
            Optional<TicketInventoryEntity> inv = ticketInventoryRepository.findById(ticketTypeId);
            if (inv.isPresent()) {
                int avail = inv.get().getAvailable();
                redisTemplate.opsForValue().set(AVAIL_KEY_PREFIX + ticketTypeId, String.valueOf(avail), AVAIL_TTL);
            }
        } catch (Exception ex) {
            log.warn("Failed to sync Redis inventory counter for ticketTypeId={}: {}", ticketTypeId, ex.getMessage());
        }
    }

    public int reconcileAllFromMySql() {
        List<TicketInventoryEntity> all = ticketInventoryRepository.findAll();
        int repaired = 0;
        for (TicketInventoryEntity inv : all) {
            String key = AVAIL_KEY_PREFIX + inv.getTicketTypeId();
            int expectedAvail = inv.getAvailable();
            try {
                String currentRaw = redisTemplate.opsForValue().get(key);
                if (currentRaw == null || Integer.parseInt(currentRaw) != expectedAvail) {
                    redisTemplate.opsForValue().set(key, String.valueOf(expectedAvail), AVAIL_TTL);
                    repaired++;
                }
            } catch (Exception ex) {
                log.warn("Reconciliation failed for ticketTypeId={}: {}", inv.getTicketTypeId(), ex.getMessage());
            }
        }
        return repaired;
    }

    private void warmMissingCounters(List<InventoryService.SeatRequest> requests) {
        List<UUID> ids = new ArrayList<>();
        for (InventoryService.SeatRequest req : requests) {
            ids.add(req.ticketTypeId());
        }
        for (TicketInventoryEntity inv : ticketInventoryRepository.findAllById(ids)) {
            redisTemplate.opsForValue().setIfAbsent(
                    AVAIL_KEY_PREFIX + inv.getTicketTypeId(),
                    String.valueOf(inv.getAvailable()),
                    AVAIL_TTL
            );
        }
    }

    private static long toLong(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(val));
    }
}

