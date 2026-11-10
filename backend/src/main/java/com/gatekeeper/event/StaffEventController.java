package com.gatekeeper.event;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/staff")
public class StaffEventController {

    private final EventService eventService;

    public StaffEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventDtos.EventSummaryResponse>> listAssignedEvents() {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(eventService.listStaffAssignedEvents(principal));
    }
}

