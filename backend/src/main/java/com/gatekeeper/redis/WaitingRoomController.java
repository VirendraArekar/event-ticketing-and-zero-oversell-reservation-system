package com.gatekeeper.redis;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/events/{eventId}/waiting-room")
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    public WaitingRoomController(WaitingRoomService waitingRoomService) {
        this.waitingRoomService = waitingRoomService;
    }

    @PostMapping("/enter")
    public ResponseEntity<WaitingRoomService.WaitingRoomStatusResponse> enter(@PathVariable UUID eventId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(waitingRoomService.enter(eventId, principal.userId()));
    }

    @GetMapping("/status")
    public ResponseEntity<WaitingRoomService.WaitingRoomStatusResponse> status(
            @PathVariable UUID eventId,
            @RequestParam String token
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(waitingRoomService.getStatus(eventId, principal.userId(), token));
    }
}

