package com.gatekeeper.notification;

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
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<NotificationService.NotificationPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(notificationService.listMyNotifications(principal, page, size));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<NotificationService.NotificationResponse> markRead(@PathVariable UUID notificationId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(notificationService.markAsRead(notificationId, principal));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        notificationService.markAllAsRead(principal);
        return ResponseEntity.noContent().build();
    }
}

