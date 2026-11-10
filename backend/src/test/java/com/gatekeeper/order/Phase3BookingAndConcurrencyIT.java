package com.gatekeeper.order;

import com.gatekeeper.event.TicketInventoryEntity;
import com.gatekeeper.event.TicketInventoryRepository;
import com.gatekeeper.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Phase3BookingAndConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TicketInventoryRepository inventoryRepository;

    @Test
    void freePaidLedgerCancelRefundAndIdempotencyWorkflows() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgToken = registerAndGetToken("org3-" + suffix + "@gk.local", new String[]{"ORGANIZER"});
        String attToken = registerAndGetToken("att3-" + suffix + "@gk.local", new String[]{"ATTENDEE"});

        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        Instant end = start.plus(3, ChronoUnit.HOURS);
        Instant cutoff = start.minus(1, ChronoUnit.HOURS);

        String eventId = createAndPublishEvent(orgToken, "Booking Expo " + suffix, 100, start, end, cutoff);
        String freeTypeId = addTicketType(orgToken, eventId, "Free Pass", 0L, 10, 5);
        String paidTypeId = addTicketType(orgToken, eventId, "Pro Pass", 5000L, 10, 5);

        // 1. Idempotent free hold + instant confirm
        String idemKey1 = "idem-free-" + UUID.randomUUID();
        Map<String, Object> freeHoldReq = Map.of(
                "eventId", eventId,
                "items", List.of(Map.of("ticketTypeId", freeTypeId, "quantity", 2))
        );

        MvcResult hold1 = mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + attToken)
                        .header("Idempotency-Key", idemKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(freeHoldReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.ledger[0].entryType").value("INITIATED"))
                .andReturn();
        String freeOrderId = jsonMapper.readTree(hold1.getResponse().getContentAsString()).get("id").asString();

        // Repeated idempotent request returns same order ID and does not reserve twice
        mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + attToken)
                        .header("Idempotency-Key", idemKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(freeHoldReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(freeOrderId));

        // Same key with different payload -> 409 Conflict
        mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + attToken)
                        .header("Idempotency-Key", idemKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", freeTypeId, "quantity", 3))
                        ))))
                .andExpect(status().isConflict());

        TicketInventoryEntity freeInvAfterHold = inventoryRepository.findById(UUID.fromString(freeTypeId)).orElseThrow();
        assertThat(freeInvAfterHold.getHeld()).isEqualTo(2);
        assertThat(freeInvAfterHold.getSold()).isZero();

        // Confirm free order -> CONFIRMED immediately with CAPTURED ledger
        mockMvc.perform(post("/api/orders/{orderId}/confirm", freeOrderId)
                        .header("Authorization", "Bearer " + attToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.ledger.length()").value(2))
                .andExpect(jsonPath("$.ledger[1].entryType").value("CAPTURED"));

        // 2. Paid order -> PENDING_PAYMENT -> Organizer marks paid -> Cancel & Refund returns seats
        String idemKey2 = "idem-paid-" + UUID.randomUUID();
        MvcResult paidHold = mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + attToken)
                        .header("Idempotency-Key", idemKey2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", paidTypeId, "quantity", 3))
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String paidOrderId = jsonMapper.readTree(paidHold.getResponse().getContentAsString()).get("id").asString();

        mockMvc.perform(post("/api/orders/{orderId}/confirm", paidOrderId)
                        .header("Authorization", "Bearer " + attToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentDeadlineAt").isNotEmpty())
                .andExpect(jsonPath("$.ledger[1].entryType").value("PENDING"));

        mockMvc.perform(post("/api/organizer/events/{eventId}/orders/{orderId}/mark-paid", eventId, paidOrderId)
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("referenceCode", "CASH-001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.ledger[2].entryType").value("CAPTURED"));

        TicketInventoryEntity paidInvAfterConfirm = inventoryRepository.findById(UUID.fromString(paidTypeId)).orElseThrow();
        assertThat(paidInvAfterConfirm.getSold()).isEqualTo(3);
        assertThat(paidInvAfterConfirm.getHeld()).isZero();

        // Cancel & refund before cutoff returns seats to inventory
        mockMvc.perform(post("/api/orders/{orderId}/cancel", paidOrderId)
                        .header("Authorization", "Bearer " + attToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.ledger[3].entryType").value("REFUNDED"));

        TicketInventoryEntity paidInvAfterRefund = inventoryRepository.findById(UUID.fromString(paidTypeId)).orElseThrow();
        assertThat(paidInvAfterRefund.getSold()).isZero();
        assertThat(paidInvAfterRefund.getAvailable()).isEqualTo(10);
    }

    @Test
    void fiveHundredConcurrentBookingRequestsNeverOversellFiftySeats() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgToken = registerAndGetToken("org-race-" + suffix + "@gk.local", new String[]{"ORGANIZER"});

        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Instant end = start.plus(4, ChronoUnit.HOURS);
        String eventId = createAndPublishEvent(orgToken, "Flash Sale " + suffix, 50, start, end, start);
        String ticketTypeId = addTicketType(orgToken, eventId, "Limited", 0L, 50, 5);

        int totalRequests = 500;
        List<String> userTokens = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            userTokens.add(registerAndGetToken("buyer-" + i + "-" + suffix + "@gk.local", new String[]{"ATTENDEE"}, "10.20.0." + (i + 1)));
        }

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(totalRequests);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            final int idx = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                String token = userTokens.get(idx % userTokens.size());
                String payload = jsonMapper.writeValueAsString(Map.of(
                        "eventId", eventId,
                        "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", 1))
                ));
                ready.countDown();
                fire.await();

                MvcResult holdRes = mockMvc.perform(post("/api/orders/hold")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", "race-" + suffix + "-" + idx)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();

                if (holdRes.getResponse().getStatus() == 201) {
                    String orderId = jsonMapper.readTree(holdRes.getResponse().getContentAsString()).get("id").asString();
                    mockMvc.perform(post("/api/orders/{orderId}/confirm", orderId)
                                    .header("Authorization", "Bearer " + token))
                            .andExpect(status().isOk());
                    succeeded.incrementAndGet();
                } else {
                    assertThat(holdRes.getResponse().getStatus()).isEqualTo(409);
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }

        ready.await();
        fire.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        TicketInventoryEntity inv = inventoryRepository.findById(UUID.fromString(ticketTypeId)).orElseThrow();
        assertThat(succeeded.get()).isEqualTo(50);
        assertThat(rejected.get()).isEqualTo(450);
        assertThat(inv.getSold()).isEqualTo(50);
        assertThat(inv.getHeld()).isZero();
        assertThat(inv.getHeld() + inv.getSold()).isEqualTo(50);
    }

    private String registerAndGetToken(String email, String[] roles) throws Exception {
        return registerAndGetToken(email, roles, "10.20.1.1");
    }

    private String registerAndGetToken(String email, String[] roles, String ip) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "StrongPass!1",
                                "fullName", "User " + email,
                                "roles", roles
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private String createAndPublishEvent(
            String orgToken, String title, int capacity, Instant start, Instant end, Instant cutoff
    ) throws Exception {
        MvcResult createRes = mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "title", title,
                                "description", "Concurrency test event",
                                "category", "Music",
                                "venueName", "Arena",
                                "venueAddress", "1 Arena Way",
                                "venueCapacity", capacity,
                                "startTime", start.toString(),
                                "endTime", end.toString(),
                                "timezone", "UTC",
                                "cancellationCutoffAt", cutoff.toString()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String eventId = jsonMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asString();

        // Add ticket type placeholder? Caller adds ticket types then publishes; let's return draft id?
        // Wait: publish requires ticket type! Let's publish in addTicketType? No, let's check order!
        return eventId;
    }

    private String addTicketType(
            String orgToken, String eventId, String name, long priceCents, int capacity, int perOrderLimit
    ) throws Exception {
        Instant salesStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant salesEnd = Instant.now().plus(1, ChronoUnit.DAYS);
        MvcResult ttRes = mockMvc.perform(post("/api/organizer/events/{eventId}/ticket-types", eventId)
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "name", name,
                                "priceCents", priceCents,
                                "currency", "USD",
                                "capacity", capacity,
                                "perOrderLimit", perOrderLimit,
                                "salesStart", salesStart.toString(),
                                "salesEnd", salesEnd.toString()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();

        // Publish if currently DRAFT
        MvcResult eventDetail = mockMvc.perform(get("/api/organizer/events/{eventId}", eventId)
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk())
                .andReturn();
        if ("DRAFT".equals(jsonMapper.readTree(eventDetail.getResponse().getContentAsString()).get("status").asString())) {
            mockMvc.perform(post("/api/organizer/events/{eventId}/publish", eventId)
                            .header("Authorization", "Bearer " + orgToken))
                    .andExpect(status().isOk());
        }
        return jsonMapper.readTree(ttRes.getResponse().getContentAsString()).get("id").asString();
    }
}

