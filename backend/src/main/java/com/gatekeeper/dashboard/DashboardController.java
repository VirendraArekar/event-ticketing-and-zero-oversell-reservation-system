package com.gatekeeper.dashboard;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.SecurityUtils;
import com.gatekeeper.common.web.PageResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/organizer/events/{eventId}")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardDtos.DashboardResponse> getDashboard(@PathVariable UUID eventId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(dashboardService.getDashboard(eventId, principal));
    }

    @GetMapping("/attendees")
    public ResponseEntity<PageResponse<DashboardDtos.AttendeeRecord>> listAttendees(
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(dashboardService.listAttendees(eventId, principal, page, size));
    }

    @GetMapping(value = "/attendees/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportAttendeesCsv(@PathVariable UUID eventId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        byte[] csv = dashboardService.exportAttendeesCsv(eventId, principal);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("attendees-" + eventId + ".csv")
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }
}

