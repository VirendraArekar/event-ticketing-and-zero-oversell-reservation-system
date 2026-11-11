package com.gatekeeper.messaging;

import com.gatekeeper.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Phase6KafkaOutboxDashboardWaitlistIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void outboxKafkaConsumersDashboardAggregatesNotificationsAndCsvExport() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgToken = register("org6-" + suffix + "@gk.local", "Organizer Six", new String[]{"ORGANIZER"}, "10.40.0.1");
        String att1Token = register("att6a-" + suffix + "@gk.local", "Buyer One", new String[]{"ATTENDEE"}, "10.40.0.2");
        String att2Token = register("att6b-" + suffix + "@gk.local", "Buyer Two", new String[]{"ATTENDEE"}, "10.40.0.3");
        String staffEmail = "staff6-" + suffix + "@gk.local";
        String staffToken = register(staffEmail, "Door Staff", new String[]{"ATTENDEE"}, "10.40.0.4");

        Instant now = Instant.now();
        String eventId = createEvent(orgToken, "Analytics Summit " + suffix, 10, now.plus(30, ChronoUnit.MINUTES), now.plus(4, ChronoUnit.HOURS));
        String vipId = addTicketType(orgToken, eventId, "VIP", 10000L, 5, 5);
        String gaId = addTicketType(orgToken, eventId, "GA", 2000L, 5, 5);
        publishEvent(orgToken, eventId);

        mockMvc.perform(post("/api/organizer/events/{eventId}/staff", eventId)
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", staffEmail))))
                .andExpect(status().isCreated());

        // Buyer 1 buys 2 VIP ($200 total)
        String order1Id = holdOrder(att1Token, eventId, vipId, 2, "k6-1-" + suffix);
        mockMvc.perform(post("/api/orders/{orderId}/confirm", order1Id)
                        .header("Authorization", "Bearer " + att1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        mockMvc.perform(post("/api/organizer/events/{eventId}/orders/{orderId}/mark-paid", eventId, order1Id)
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Buyer 2 buys 1 GA ($20 total)
        String order2Id = holdOrder(att2Token, eventId, gaId, 1, "k6-2-" + suffix);
        mockMvc.perform(post("/api/orders/{orderId}/confirm", order2Id)
                        .header("Authorization", "Bearer " + att2Token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/organizer/events/{eventId}/orders/{orderId}/mark-paid", eventId, order2Id)
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk());

        // Staff checks in 1 of Buyer 1's VIP tickets
        MvcResult tRes = mockMvc.perform(get("/api/tickets")
                        .header("Authorization", "Bearer " + att1Token)
                        .param("eventId", eventId))
                .andExpect(status().isOk())
                .andReturn();
        String qrToken = jsonMapper.readTree(tRes.getResponse().getContentAsString())
                .get("items").get(0).get("qrToken").asString();

        mockMvc.perform(post("/api/events/{eventId}/checkin", eventId)
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("qrToken", qrToken))))
                .andExpect(status().isOk());

        // Wait for async outbox relay + Kafka consumers to update dashboard & notifications
        awaitCondition(() -> {
            MvcResult dashRes = mockMvc.perform(get("/api/organizer/events/{eventId}/dashboard", eventId)
                            .header("Authorization", "Bearer " + orgToken))
                    .andReturn();
            if (dashRes.getResponse().getStatus() != 200) return false;
            JsonNode d = jsonMapper.readTree(dashRes.getResponse().getContentAsString());
            return d.get("totalSold").asInt() == 3 && d.get("totalCheckedIn").asInt() == 1;
        }, 15_000);

        mockMvc.perform(get("/api/organizer/events/{eventId}/dashboard", eventId)
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTicketCapacity").value(10))
                .andExpect(jsonPath("$.totalSold").value(3))
                .andExpect(jsonPath("$.totalRevenueCents").value(22000))
                .andExpect(jsonPath("$.totalCheckedIn").value(1))
                .andExpect(jsonPath("$.noShowCount").value(2))
                .andExpect(jsonPath("$.capacityUsedPercent").value(30.0))
                .andExpect(jsonPath("$.topTicketTypes[0].name").value("VIP"))
                .andExpect(jsonPath("$.checkInCurve.length()").value(1))
                .andExpect(jsonPath("$.salesOverTime.length()").value(1));

        // Verify Buyer 1 received notifications (PAYMENT_PENDING, ORDER_CONFIRMED, CHECKIN_RECORDED)
        awaitCondition(() -> {
            MvcResult notifRes = mockMvc.perform(get("/api/notifications")
                            .header("Authorization", "Bearer " + att1Token))
                    .andReturn();
            JsonNode n = jsonMapper.readTree(notifRes.getResponse().getContentAsString());
            return n.get("items").size() >= 3;
        }, 10_000);

        MvcResult notifRes = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + att1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andReturn();
        String firstNotifId = jsonMapper.readTree(notifRes.getResponse().getContentAsString())
                .get("items").get(0).get("id").asString();
        mockMvc.perform(post("/api/notifications/{id}/read", firstNotifId)
                        .header("Authorization", "Bearer " + att1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        // Verify CSV export
        MvcResult csvRes = mockMvc.perform(get("/api/organizer/events/{eventId}/attendees/export", eventId)
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk())
                .andReturn();
        String csv = csvRes.getResponse().getContentAsString();
        assertThat(csv).contains("ticket_code,ticket_type,attendee_name,attendee_email,status");
        assertThat(csv).contains("Buyer One", "Buyer Two", "VIP", "GA", "CHECKED_IN");

        // Verify consumer deduplication: re-sending an already-published OrderConfirmed message does not double-count
        OutboxEventEntity sampleOrderEvent = outboxEventRepository.findAll().stream()
                .filter(e -> "OrderConfirmed".equals(e.getEventType()) && eventId.equals(e.getPartitionKey()))
                .findFirst()
                .orElseThrow();
        kafkaTemplate.send(sampleOrderEvent.getTopic(), sampleOrderEvent.getPartitionKey(), sampleOrderEvent.getPayload()).get();
        Thread.sleep(1000L);
        mockMvc.perform(get("/api/organizer/events/{eventId}/dashboard", eventId)
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSold").value(3))
                .andExpect(jsonPath("$.totalRevenueCents").value(22000));
    }

    @Test
    void waitlistPromotionFifoAndPoisonMessageDeadLetterTopic() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgToken = register("org-wl-" + suffix + "@gk.local", "Org WL", new String[]{"ORGANIZER"}, "10.40.1.1");
        String aToken = register("wl-a-" + suffix + "@gk.local", "Attendee A", new String[]{"ATTENDEE"}, "10.40.1.2");
        String bToken = register("wl-b-" + suffix + "@gk.local", "Attendee B", new String[]{"ATTENDEE"}, "10.40.1.3");
        String cToken = register("wl-c-" + suffix + "@gk.local", "Attendee C", new String[]{"ATTENDEE"}, "10.40.1.4");

        Instant now = Instant.now();
        String eventId = createEvent(orgToken, "Sold Out Show " + suffix, 2, now.plus(2, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS));
        String ttId = addTicketType(orgToken, eventId, "Front Row", 0L, 2, 2);
        publishEvent(orgToken, eventId);

        // Attendee A takes all 2 seats
        String orderA = holdOrder(aToken, eventId, ttId, 2, "wl-a-" + suffix);
        mockMvc.perform(post("/api/orders/{orderId}/confirm", orderA)
                        .header("Authorization", "Bearer " + aToken))
                .andExpect(status().isOk());

        // B and C join waitlist (1 seat each)
        mockMvc.perform(post("/api/events/{eventId}/waitlist", eventId)
                        .header("Authorization", "Bearer " + bToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("ticketTypeId", ttId, "quantity", 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1));

        mockMvc.perform(post("/api/events/{eventId}/waitlist", eventId)
                        .header("Authorization", "Bearer " + cToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("ticketTypeId", ttId, "quantity", 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(2));

        // Attendee A cancels -> 2 seats freed -> Outbox -> Kafka WaitlistConsumer promotes B & C!
        mockMvc.perform(post("/api/orders/{orderId}/cancel", orderA)
                        .header("Authorization", "Bearer " + aToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        awaitCondition(() -> {
            MvcResult wlRes = mockMvc.perform(get("/api/waitlist/me")
                            .header("Authorization", "Bearer " + bToken))
                    .andReturn();
            JsonNode wl = jsonMapper.readTree(wlRes.getResponse().getContentAsString());
            return wl.get("items").size() == 1 && "OFFERED".equals(wl.get("items").get(0).get("status").asString());
        }, 15_000);

        MvcResult bWaitlist = mockMvc.perform(get("/api/waitlist/me")
                        .header("Authorization", "Bearer " + bToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("OFFERED"))
                .andExpect(jsonPath("$.items[0].offerOrderId").isNotEmpty())
                .andReturn();
        String offerOrderB = jsonMapper.readTree(bWaitlist.getResponse().getContentAsString())
                .get("items").get(0).get("offerOrderId").asString();

        // Attendee B confirms the waitlist hold -> CLAIMED
        mockMvc.perform(post("/api/orders/{orderId}/confirm", offerOrderB)
                        .header("Authorization", "Bearer " + bToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/waitlist/me")
                        .header("Authorization", "Bearer " + bToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("CLAIMED"));

        // Verify poison message routes to dead-letter topic order-events.DLT
        String poisonKey = UUID.randomUUID().toString();
        kafkaTemplate.send(KafkaTopics.ORDER_EVENTS, poisonKey, "{invalid-json-poison").get();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-verifier-" + suffix);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        boolean foundPoisonInDlt = false;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(KafkaTopics.ORDER_EVENTS + ".DLT"));
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline && !foundPoisonInDlt) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    if (poisonKey.equals(rec.key()) && "{invalid-json-poison".equals(rec.value())) {
                        foundPoisonInDlt = true;
                        break;
                    }
                }
            }
        }
        assertThat(foundPoisonInDlt).isTrue();
    }

    @FunctionalInterface
    private interface ConditionCheck {
        boolean evaluate() throws Exception;
    }

    private void awaitCondition(ConditionCheck check, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.evaluate()) {
                return;
            }
            Thread.sleep(250L);
        }
        assertThat(check.evaluate()).isTrue();
    }

    private String register(String email, String fullName, String[] roles, String ip) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "StrongPass!1",
                                "fullName", fullName,
                                "roles", roles
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private String createEvent(String orgToken, String title, int cap, Instant start, Instant end) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "title", title,
                                "description", "Kafka integration test",
                                "category", "Tech",
                                "venueName", "Grand Arena",
                                "venueAddress", "500 Broadway",
                                "venueCapacity", cap,
                                "startTime", start.toString(),
                                "endTime", end.toString(),
                                "timezone", "UTC"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }

    private String addTicketType(String orgToken, String eventId, String name, long price, int cap, int limit) throws Exception {
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

    private String holdOrder(String token, String eventId, String ttId, int qty, String idemKey) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ttId, "quantity", qty))
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }
}

