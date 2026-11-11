package com.gatekeeper.checkin;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class Phase5TicketsAndCheckInIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void ticketIssuingQrPdfDownloadsConcurrentCheckInAndWindowValidation() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgToken = register("org5-" + suffix + "@gk.local", "Organizer Five", new String[]{"ORGANIZER"}, "10.30.0.1");
        String attToken = register("att5-" + suffix + "@gk.local", "Attendee Five", new String[]{"ATTENDEE"}, "10.30.0.2");
        String otherToken = register("other5-" + suffix + "@gk.local", "Other User", new String[]{"ATTENDEE"}, "10.30.0.3");
        String staff1Email = "staff5a-" + suffix + "@gk.local";
        String staff2Email = "staff5b-" + suffix + "@gk.local";
        String staff1Token = register(staff1Email, "Staff One", new String[]{"ATTENDEE"}, "10.30.0.4");
        String staff2Token = register(staff2Email, "Staff Two", new String[]{"ATTENDEE"}, "10.30.0.5");

        Instant now = Instant.now();
        // Active check-in window: starts in 30m, ends in 4h
        String eventId = createPublishEvent(orgToken, "Door Scan Conf " + suffix, 20, now.plus(30, ChronoUnit.MINUTES), now.plus(4, ChronoUnit.HOURS));
        String ttId = addTicketTypeAndPublish(orgToken, eventId, "Entry", 0L, 10, 5);

        assignStaff(orgToken, eventId, staff1Email);
        assignStaff(orgToken, eventId, staff2Email);

        // Book and confirm 3 free tickets
        String orderId = holdAndConfirmFree(attToken, eventId, ttId, 3, "idem-5-" + suffix);

        // Fetch attendee tickets
        MvcResult ticketsRes = mockMvc.perform(get("/api/tickets")
                        .header("Authorization", "Bearer " + attToken)
                        .param("eventId", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn();

        JsonNode items = jsonMapper.readTree(ticketsRes.getResponse().getContentAsString()).get("items");
        String t1Id = items.get(0).get("id").asString();
        String t1Qr = items.get(0).get("qrToken").asString();
        String t2Id = items.get(1).get("id").asString();
        String t2Qr = items.get(1).get("qrToken").asString();
        String t3Code = items.get(2).get("ticketCode").asString();

        // Download QR PNG and PDF
        mockMvc.perform(get("/api/tickets/{id}/qr", t1Id)
                        .header("Authorization", "Bearer " + attToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));

        mockMvc.perform(get("/api/tickets/{id}/pdf", t1Id)
                        .header("Authorization", "Bearer " + attToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".pdf")));

        // Tenant isolation: other attendee cannot read or download ticket
        mockMvc.perform(get("/api/tickets/{id}", t1Id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        // Unassigned user cannot check in
        mockMvc.perform(post("/api/events/{eventId}/checkin", eventId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("qrToken", t1Qr))))
                .andExpect(status().isForbidden());

        // Staff 1 checks in ticket 1 via QR token
        mockMvc.perform(post("/api/events/{eventId}/checkin", eventId)
                        .header("Authorization", "Bearer " + staff1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("qrToken", t1Qr))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.checkedInByName").value("Staff One"));

        // Duplicate scan by Staff 2 rejects with clear message
        mockMvc.perform(post("/api/events/{eventId}/checkin", eventId)
                        .header("Authorization", "Bearer " + staff2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("qrToken", t1Qr))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Already checked in at")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Staff One")));

        // Concurrent double check-in of ticket 2 from two threads -> exactly one succeeds
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        Future<?> f1 = pool.submit(() -> {
            ready.countDown();
            fire.await();
            MvcResult r = mockMvc.perform(post("/api/events/{eventId}/checkin", eventId)
                            .header("Authorization", "Bearer " + staff1Token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(Map.of("qrToken", t2Qr))))
                    .andReturn();
            if (r.getResponse().getStatus() == 200) successCount.incrementAndGet();
            else if (r.getResponse().getStatus() == 409) conflictCount.incrementAndGet();
            return null;
        });
        Future<?> f2 = pool.submit(() -> {
            ready.countDown();
            fire.await();
            MvcResult r = mockMvc.perform(post("/api/events/{eventId}/checkin", eventId)
                            .header("Authorization", "Bearer " + staff2Token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(Map.of("qrToken", t2Qr))))
                    .andReturn();
            if (r.getResponse().getStatus() == 200) successCount.incrementAndGet();
            else if (r.getResponse().getStatus() == 409) conflictCount.incrementAndGet();
            return null;
        });

        ready.await();
        fire.countDown();
        f1.get();
        f2.get();
        pool.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Manual ticket code fallback check-in on ticket 3
        mockMvc.perform(post("/api/events/{eventId}/checkin", eventId)
                        .header("Authorization", "Bearer " + staff1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("ticketCode", t3Code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        // Forged QR token rejected
        mockMvc.perform(post("/api/events/{eventId}/checkin", eventId)
                        .header("Authorization", "Bearer " + staff1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("qrToken", t2Qr + "tampered"))))
                .andExpect(status().isBadRequest());

        // Cannot refund an order after tickets are checked in
        mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + attToken))
                .andExpect(status().isConflict());

        // Event outside check-in window (5 days away) rejects early scan
        String futureEventId = createPublishEvent(orgToken, "Future Conf " + suffix, 10, now.plus(5, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS));
        String futureTtId = addTicketTypeAndPublish(orgToken, futureEventId, "Pass", 0L, 5, 2);
        assignStaff(orgToken, futureEventId, staff1Email);
        holdAndConfirmFree(attToken, futureEventId, futureTtId, 1, "idem-future-" + suffix);

        MvcResult futureTickets = mockMvc.perform(get("/api/tickets")
                        .header("Authorization", "Bearer " + attToken)
                        .param("eventId", futureEventId))
                .andExpect(status().isOk())
                .andReturn();
        String futureQr = jsonMapper.readTree(futureTickets.getResponse().getContentAsString())
                .get("items").get(0).get("qrToken").asString();

        mockMvc.perform(post("/api/events/{eventId}/checkin", futureEventId)
                        .header("Authorization", "Bearer " + staff1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("qrToken", futureQr))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Check-in window is not open yet")));

        // Verify recent check-in logs recorded
        mockMvc.perform(get("/api/events/{eventId}/checkin/recent", eventId)
                        .header("Authorization", "Bearer " + staff1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
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

    private String createPublishEvent(String orgToken, String title, int cap, Instant start, Instant end) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "title", title,
                                "description", "Check-in test event",
                                "category", "Conference",
                                "venueName", "Main Hall",
                                "venueAddress", "10 Event Way",
                                "venueCapacity", cap,
                                "startTime", start.toString(),
                                "endTime", end.toString(),
                                "timezone", "UTC"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }

    private String addTicketTypeAndPublish(
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
        mockMvc.perform(post("/api/organizer/events/{eventId}/publish", eventId)
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk());
        return jsonMapper.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }

    private void assignStaff(String orgToken, String eventId, String email) throws Exception {
        mockMvc.perform(post("/api/organizer/events/{eventId}/staff", eventId)
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isCreated());
    }

    private String holdAndConfirmFree(String attToken, String eventId, String ttId, int qty, String idemKey) throws Exception {
        MvcResult holdRes = mockMvc.perform(post("/api/orders/hold")
                        .header("Authorization", "Bearer " + attToken)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "eventId", eventId,
                                "items", List.of(Map.of("ticketTypeId", ttId, "quantity", qty))
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = jsonMapper.readTree(holdRes.getResponse().getContentAsString()).get("id").asString();
        mockMvc.perform(post("/api/orders/{orderId}/confirm", orderId)
                        .header("Authorization", "Bearer " + attToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        return orderId;
    }
}

