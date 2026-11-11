package com.gatekeeper.checkin;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.SecurityUtils;
import com.gatekeeper.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/events/{eventId}/checkin")
public class CheckInController {

    private final CheckInService checkInService;

    public CheckInController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @PostMapping
    public ResponseEntity<CheckInDtos.CheckInResponse> checkIn(
            @PathVariable UUID eventId,
            @Valid @RequestBody CheckInDtos.CheckInRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(checkInService.checkIn(eventId, principal, request));
    }

    @GetMapping("/recent")
    public ResponseEntity<PageResponse<CheckInDtos.CheckInLogResponse>> listRecent(
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(checkInService.listRecentLogs(eventId, principal, page, size));
    }
}

