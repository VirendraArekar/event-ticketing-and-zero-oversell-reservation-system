package com.gatekeeper.event;

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
class Phase2AuthAndEventIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void authLifecycleEventCreationTicketTypesCapacityGuardPublishAndOwnershipIsolation() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgAEmail = "orga-" + suffix + "@gatekeeper.local";
        String orgBEmail = "orgb-" + suffix + "@gatekeeper.local";
        String attendeeEmail = "att-" + suffix + "@gatekeeper.local";
        String staffEmail = "staff-" + suffix + "@gatekeeper.local";

        JsonNode orgAAuth = register(orgAEmail, "StrongPass!1", "Organizer A", new String[]{"ORGANIZER"});
        String orgAToken = orgAAuth.get("accessToken").asString();
        String orgARefresh = orgAAuth.get("refreshToken").asString();

        JsonNode orgBAuth = register(orgBEmail, "StrongPass!1", "Organizer B", new String[]{"ORGANIZER"});
        String orgBToken = orgBAuth.get("accessToken").asString();

        JsonNode attAuth = register(attendeeEmail, "StrongPass!1", "Attendee One", new String[]{"ATTENDEE"});
        String attToken = attAuth.get("accessToken").asString();

        JsonNode staffAuth = register(staffEmail, "StrongPass!1", "Staff Member", new String[]{"ATTENDEE"});
        String staffToken = staffAuth.get("accessToken").asString();

        // Refresh token rotation + old token rejection
        MvcResult refreshRes = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("refreshToken", orgARefresh))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode refreshed = jsonMapper.readTree(refreshRes.getResponse().getContentAsString());
        String newOrgARefresh = refreshed.get("refreshToken").asString();
        orgAToken = refreshed.get("accessToken").asString();
        assertThat(newOrgARefresh).isNotEqualTo(orgARefresh);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("refreshToken", orgARefresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        // Validation error format check
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "email", "not-an-email",
                                "password", "short",
                                "fullName", ""
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        // Attendee cannot create event
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant end = start.plus(4, ChronoUnit.HOURS);
        Map<String, Object> eventPayload = Map.of(
                "title", "Systems Conf " + suffix,
                "description", "Deep dive into distributed systems",
                "category", "Technology",
                "venueName", "Grand Hall",
                "venueAddress", "100 Harbor Blvd",
                "venueCapacity", 100,
                "startTime", start.toString(),
                "endTime", end.toString(),
                "timezone", "America/New_York"
        );

        mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", "Bearer " + attToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(eventPayload)))
                .andExpect(status().isForbidden());

        // Organizer A creates DRAFT event
        MvcResult createEventRes = mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", "Bearer " + orgAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(eventPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String eventId = jsonMapper.readTree(createEventRes.getResponse().getContentAsString()).get("id").asString();

        // Draft event is not publicly visible
        mockMvc.perform(get("/api/events/{eventId}", eventId))
                .andExpect(status().isNotFound());

        // Cannot publish without ticket types
        mockMvc.perform(post("/api/organizer/events/{eventId}/publish", eventId)
                        .header("Authorization", "Bearer " + orgAToken))
                .andExpect(status().isBadRequest());

        Instant salesStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant salesEnd = start.minus(1, ChronoUnit.HOURS);

        // Add General Admission (70 seats)
        mockMvc.perform(post("/api/organizer/events/{eventId}/ticket-types", eventId)
                        .header("Authorization", "Bearer " + orgAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "name", "General",
                                "description", "Main floor",
                                "priceCents", 4500,
                                "currency", "USD",
                                "capacity", 70,
                                "perOrderLimit", 5,
                                "salesStart", salesStart.toString(),
                                "salesEnd", salesEnd.toString()
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.available").value(70));

        // Exceeding venue capacity (70 + 40 > 100) must fail
        mockMvc.perform(post("/api/organizer/events/{eventId}/ticket-types", eventId)
                        .header("Authorization", "Bearer " + orgAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "name", "VIP",
                                "priceCents", 12000,
                                "currency", "USD",
                                "capacity", 40,
                                "perOrderLimit", 4,
                                "salesStart", salesStart.toString(),
                                "salesEnd", salesEnd.toString()
                        ))))
                .andExpect(status().isBadRequest());

        // Adding 30 VIP seats (70 + 30 == 100) succeeds
        mockMvc.perform(post("/api/organizer/events/{eventId}/ticket-types", eventId)
                        .header("Authorization", "Bearer " + orgAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "name", "VIP",
                                "priceCents", 12000,
                                "currency", "USD",
                                "capacity", 30,
                                "perOrderLimit", 4,
                                "salesStart", salesStart.toString(),
                                "salesEnd", salesEnd.toString()
                        ))))
                .andExpect(status().isCreated());

        // Organizer B cannot modify or publish Organizer A's event
        mockMvc.perform(put("/api/organizer/events/{eventId}", eventId)
                        .header("Authorization", "Bearer " + orgBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(eventPayload)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/organizer/events/{eventId}/publish", eventId)
                        .header("Authorization", "Bearer " + orgBToken))
                .andExpect(status().isForbidden());

        // Organizer A publishes
        mockMvc.perform(post("/api/organizer/events/{eventId}/publish", eventId)
                        .header("Authorization", "Bearer " + orgAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Public search & detail find published event with Cache-Control
        mockMvc.perform(get("/api/events")
                        .param("search", suffix)
                        .param("category", "Technology")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Cache-Control"))
                .andExpect(jsonPath("$.items[0].id").value(eventId))
                .andExpect(jsonPath("$.items[0].totalAvailable").value(100));

        mockMvc.perform(get("/api/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketTypes.length()").value(2));

        // Staff assignment
        mockMvc.perform(post("/api/organizer/events/{eventId}/staff", eventId)
                        .header("Authorization", "Bearer " + orgAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", staffEmail))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(staffEmail));

        mockMvc.perform(get("/api/staff/events")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(eventId));

        // Logout revokes refresh token
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + orgAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("refreshToken", newOrgARefresh))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("refreshToken", newOrgARefresh))))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode register(String email, String password, String fullName, String[] roles) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password,
                                "fullName", fullName,
                                "roles", roles
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }
}
