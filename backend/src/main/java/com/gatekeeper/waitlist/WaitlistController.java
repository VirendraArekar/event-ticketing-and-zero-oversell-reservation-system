package com.gatekeeper.waitlist;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping("/events/{eventId}/waitlist")
    public ResponseEntity<WaitlistDtos.WaitlistEntryResponse> join(
            @PathVariable UUID eventId,
            @Valid @RequestBody WaitlistDtos.JoinWaitlistRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(waitlistService.joinWaitlist(eventId, principal, request));
    }

    @GetMapping("/waitlist/me")
    public ResponseEntity<PageResponse<WaitlistDtos.WaitlistEntryResponse>> myWaitlist(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(waitlistService.listMyWaitlist(principal, page, size));
    }

    @DeleteMapping("/waitlist/{entryId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID entryId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        waitlistService.cancelWaitlistEntry(entryId, principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/organizer/events/{eventId}/waitlist")
    public ResponseEntity<PageResponse<WaitlistDtos.WaitlistEntryResponse>> eventWaitlist(
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(waitlistService.listEventWaitlist(eventId, principal, page, size));
    }
}

