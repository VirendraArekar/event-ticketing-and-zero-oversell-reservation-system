package com.gatekeeper.event;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.SecurityUtils;
import com.gatekeeper.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizer/events")
public class OrganizerEventController {

    private final EventService eventService;

    public OrganizerEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventDtos.EventDetailResponse> createEvent(
            @Valid @RequestBody EventDtos.CreateOrUpdateEventRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventService.createEvent(principal, request));
    }

    @GetMapping
    public ResponseEntity<PageResponse<EventDtos.EventSummaryResponse>> listMyEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.listOrganizerEvents(principal, page, size));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventDtos.EventDetailResponse> getMyEvent(@PathVariable UUID eventId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.getOrganizerEventDetail(eventId, principal));
    }

    @PutMapping("/{eventId}")
    public ResponseEntity<EventDtos.EventDetailResponse> updateEvent(
            @PathVariable UUID eventId,
            @Valid @RequestBody EventDtos.CreateOrUpdateEventRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.updateEvent(eventId, principal, request));
    }

    @PostMapping("/{eventId}/publish")
    public ResponseEntity<EventDtos.EventDetailResponse> publishEvent(@PathVariable UUID eventId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.publishEvent(eventId, principal));
    }

    @PostMapping("/{eventId}/cancel")
    public ResponseEntity<EventDtos.EventDetailResponse> cancelEvent(@PathVariable UUID eventId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.cancelEvent(eventId, principal));
    }

    @PostMapping("/{eventId}/complete")
    public ResponseEntity<EventDtos.EventDetailResponse> completeEvent(@PathVariable UUID eventId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.completeEvent(eventId, principal));
    }

    @PostMapping("/{eventId}/ticket-types")
    public ResponseEntity<EventDtos.TicketTypeResponse> createTicketType(
            @PathVariable UUID eventId,
            @Valid @RequestBody EventDtos.CreateOrUpdateTicketTypeRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventService.createTicketType(eventId, principal, request));
    }

    @PutMapping("/{eventId}/ticket-types/{ticketTypeId}")
    public ResponseEntity<EventDtos.TicketTypeResponse> updateTicketType(
            @PathVariable UUID eventId,
            @PathVariable UUID ticketTypeId,
            @Valid @RequestBody EventDtos.CreateOrUpdateTicketTypeRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.updateTicketType(eventId, ticketTypeId, principal, request));
    }

    @PostMapping("/{eventId}/staff")
    public ResponseEntity<EventDtos.StaffAssignmentResponse> assignStaff(
            @PathVariable UUID eventId,
            @Valid @RequestBody EventDtos.AssignStaffRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventService.assignStaff(eventId, principal, request));
    }

    @GetMapping("/{eventId}/staff")
    public ResponseEntity<List<EventDtos.StaffAssignmentResponse>> listStaff(@PathVariable UUID eventId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.listStaff(eventId, principal));
    }

    @DeleteMapping("/{eventId}/staff/{staffUserId}")
    public ResponseEntity<Void> removeStaff(
            @PathVariable UUID eventId,
            @PathVariable UUID staffUserId
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        eventService.removeStaff(eventId, staffUserId, principal);
        return ResponseEntity.noContent().build();
    }
}

