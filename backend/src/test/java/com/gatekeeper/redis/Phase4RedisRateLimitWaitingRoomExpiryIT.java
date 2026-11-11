package com.gatekeeper.redis;

import com.gatekeeper.event.TicketInventoryEntity;
import com.gatekeeper.event.TicketInventoryRepository;
import com.gatekeeper.order.HoldExpiryJob;
import com.gatekeeper.order.OrderTransactionService;
import com.gatekeeper.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Phase4RedisRateLimitWaitingRoomExpiryIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TicketInventoryRepository inventoryRepository;

    @Autowired
    private RedisInventoryService redisInventoryService;

    @Autowired
    private HoldExpiryJob holdExpiryJob;

    @Autowired
    private OrderTransactionService orderTransactionService;

    @Test
    void holdExpiryReconciliationAndEventCachingWorkEndToEnd() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgToken = register("org4-" + suffix + "@gk.local", new String[]{"ORGANIZER"}, "198.51.100.10");
        String att1Token = register("att4a-" + suffix + "@gk.local", new String[]{"ATTENDEE"}, "198.51.100.11");
        String att2Token = register("att4b-" + suffix + "@gk.local", new String[]{"ATTENDEE"}, "198.51.100.12");

        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Instant end = start.plus(3, ChronoUnit.HOURS);
        String eventId = createEvent(orgToken, "Redis Test " + suffix, 5, start, end, false, 25);
        String ticketTypeId = addTicketType(orgToken, eventId, "Seat", 0L, 5, 5);
        publishEvent(orgToken, eventId);

        // Cache population + invalidation on update
        mockMvc.perform(get("/api/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Redis Test " + suffix));
        assertThat(redisTemplate.hasKey("gatekeeper:cache:event:detail:" + eventId)).isTrue();

        mockMvc.perform(put("/api/organizer/events/{eventId}", eventId)
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "title", "Updated Redis Test " + suffix,
                                "description", "Updated description",
                                "category", "Tech",
                                "venueName", "Hall A",
                                "venueAddress", "1 Main St",
                                "venueCapacity", 5,
                                "startTime", start.toString(),
                                "endTime", end.toString(),
                                "timezone", "UTC"
                        ))))
                .andExpect(status().isOk());
        assertThat(redisTemplate.hasKey("gatekeeper:cache:event:detail:" + eventId)).isFalse();

        mockMvc.perform(get("/api/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Redis Test " + suffix));

        // Hold all 5 seats
        MvcResult holdRes = mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + att1Token)
                        .header("Idempotency-Key", "hold-exp-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", 5))
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = jsonMapper.readTree(holdRes.getResponse().getContentAsString()).get("id").asString();

        // Corrupt Redis counter artificially to test MySQL source-of-truth + reconciliation
        redisTemplate.opsForValue().set("gatekeeper:inv:avail:" + ticketTypeId, "99");
        mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + att2Token)
                        .header("Idempotency-Key", "oversell-stale-redis-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", 1))
                        ))))
                .andExpect(status().isConflict());

        // Verify reconciliation repairs Redis to 0
        redisTemplate.opsForValue().set("gatekeeper:inv:avail:" + ticketTypeId, "77");
        int repaired = redisInventoryService.reconcileAllFromMySql();
        assertThat(repaired).isGreaterThanOrEqualTo(1);
        assertThat(redisTemplate.opsForValue().get("gatekeeper:inv:avail:" + ticketTypeId)).isEqualTo("0");

        // Force hold expiry timestamp into the past (UTC) & run distributed-locked HoldExpiryJob
        jdbcTemplate.update(
                "UPDATE orders SET hold_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 SECOND) WHERE id = ?",
                orderId
        );
        int expired = holdExpiryJob.runSweepOnce(Instant.now());
        assertThat(expired).isGreaterThanOrEqualTo(1);
        // Second call is idempotent
        assertThat(orderTransactionService.expireOrderIfDue(UUID.fromString(orderId), Instant.now())).isFalse();

        TicketInventoryEntity invAfterExpiry = inventoryRepository.findById(UUID.fromString(ticketTypeId)).orElseThrow();
        assertThat(invAfterExpiry.getHeld()).isZero();
        assertThat(invAfterExpiry.getAvailable()).isEqualTo(5);
        assertThat(redisTemplate.opsForValue().get("gatekeeper:inv:avail:" + ticketTypeId)).isEqualTo("5");

        // Attendee 2 can now book the released seats!
        mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + att2Token)
                        .header("Idempotency-Key", "rebook-after-exp-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", 2))
                        ))))
                .andExpect(status().isCreated());
    }

    @Test
    void rateLimiterReturns429WithRetryAfterAndRateLimitHeaders() throws Exception {
        String testIp = "203.0.113." + (10 + Math.abs(UUID.randomUUID().hashCode() % 200));
        String loginBody = jsonMapper.writeValueAsString(Map.of(
                "email", "nonexistent@gk.local",
                "password", "WrongPass123!"
        ));

        int status429Count = 0;
        for (int i = 0; i < 25; i++) {
            MvcResult res = mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", testIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody))
                    .andReturn();
            if (res.getResponse().getStatus() == 429) {
                status429Count++;
                assertThat(res.getResponse().getHeader("Retry-After")).isNotBlank();
                assertThat(res.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("20");
                assertThat(res.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo("0");
                assertThat(res.getResponse().getHeader("X-RateLimit-Reset")).isNotBlank();
                JsonNode err = jsonMapper.readTree(res.getResponse().getContentAsString());
                assertThat(err.get("message").asString()).contains("Too many");
                assertThat(err.get("traceId").asString()).isNotBlank();
                break;
            }
        }
        assertThat(status429Count).isEqualTo(1);
    }

    @Test
    void virtualWaitingRoomQueuesAndAdmitsInOrder() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgToken = register("org-wr-" + suffix + "@gk.local", new String[]{"ORGANIZER"}, "198.51.100.20");
        String user1Token = register("wr1-" + suffix + "@gk.local", new String[]{"ATTENDEE"}, "198.51.100.21");
        String user2Token = register("wr2-" + suffix + "@gk.local", new String[]{"ATTENDEE"}, "198.51.100.22");

        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Instant end = start.plus(3, ChronoUnit.HOURS);
        // Waiting room enabled with max 1 active buyer slot
        String eventId = createEvent(orgToken, "Flash Drop " + suffix, 10, start, end, true, 1);
        String ticketTypeId = addTicketType(orgToken, eventId, "Drop Pass", 0L, 10, 2);
        publishEvent(orgToken, eventId);

        // User 1 enters -> ADMITTED immediately
        MvcResult u1Enter = mockMvc.perform(post("/api/events/{eventId}/waiting-room/enter", eventId)
                        .header("Authorization", "Bearer " + user1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andReturn();
        String u1QueueToken = jsonMapper.readTree(u1Enter.getResponse().getContentAsString()).get("queueToken").asString();

        // User 2 tries booking without token -> 428 Waiting Room Required with queue token & position 1
        MvcResult u2Blocked = mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + user2Token)
                        .header("Idempotency-Key", "wr-u2-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", 1))
                        ))))
                .andExpect(status().isPreconditionRequired())
                .andExpect(header().exists("X-Waiting-Room-Token"))
                .andExpect(jsonPath("$.fieldErrors.position").value("1"))
                .andReturn();
        String u2QueueToken = u2Blocked.getResponse().getHeader("X-Waiting-Room-Token");

        // User 2 polls while User 1 holds the active slot -> still QUEUED
        mockMvc.perform(get("/api/events/{eventId}/waiting-room/status", eventId)
                        .header("Authorization", "Bearer " + user2Token)
                        .param("token", u2QueueToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.position").value(1));

        // User 1 places hold using admitted token -> consumes slot
        mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + user1Token)
                        .header("Idempotency-Key", "wr-u1-" + suffix)
                        .header("X-Waiting-Room-Token", u1QueueToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", 1))
                        ))))
                .andExpect(status().isCreated());

        // Now User 2 polls -> promoted to ADMITTED -> places hold!
        mockMvc.perform(get("/api/events/{eventId}/waiting-room/status", eventId)
                        .header("Authorization", "Bearer " + user2Token)
                        .param("token", u2QueueToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"));

        mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + user2Token)
                        .header("Idempotency-Key", "wr-u2-" + suffix)
                        .header("X-Waiting-Room-Token", u2QueueToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", 1))
                        ))))
                .andExpect(status().isCreated());
    }

    private String register(String email, String[] roles, String ip) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "StrongPass!1",
                                "fullName", "Test User",
                                "roles", roles
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private String createEvent(
            String orgToken, String title, int cap, Instant start, Instant end, boolean wrEnabled, int wrThreshold
    ) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("title", title),
                                Map.entry("description", "Event desc"),
                                Map.entry("category", "Tech"),
                                Map.entry("venueName", "Hall A"),
                                Map.entry("venueAddress", "1 Main St"),
                                Map.entry("venueCapacity", cap),
                                Map.entry("startTime", start.toString()),
                                Map.entry("endTime", end.toString()),
                                Map.entry("timezone", "UTC"),
                                Map.entry("waitingRoomEnabled", wrEnabled),
                                Map.entry("waitingRoomThreshold", wrThreshold)
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }

    private String addTicketType(
            String orgToken, String eventId, String name, long price, int cap, int limit
    ) throws Exception {
        Instant now = Instant.now();
        MvcResult res = mockMvc.perform(post("/api/organizer/events/{eventId}/ticket-types", eventId)
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "name", name,
                                "priceCents", price,
                                "currency", "USD",
                                "capacity", cap,
                                "perOrderLimit", limit,
                                "salesStart", now.minusSeconds(3600).toString(),
                                "salesEnd", now.plusSeconds(86400).toString()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }

    private void publishEvent(String orgToken, String eventId) throws Exception {
        mockMvc.perform(post("/api/organizer/events/{eventId}/publish", eventId)
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk());
    }
}
