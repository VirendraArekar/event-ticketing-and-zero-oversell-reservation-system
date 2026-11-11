package com.gatekeeper.notification;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.common.exception.ForbiddenException;
import com.gatekeeper.common.exception.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public record NotificationResponse(
            UUID id,
            UUID eventId,
            NotificationType type,
            String title,
            String message,
            UUID referenceId,
            boolean read,
            Instant createdAt
    ) {
    }

    public record NotificationPageResponse(
            List<NotificationResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            long unreadCount
    ) {
    }

    @Transactional
    public void createNotification(
            UUID userId,
            UUID eventId,
            NotificationType type,
            String title,
            String message,
            UUID referenceId,
            Instant now
    ) {
        notificationRepository.save(new NotificationEntity(
                UUID.randomUUID(),
                userId,
                eventId,
                type,
                title,
                message,
                referenceId,
                now
        ));
    }

    @Transactional(readOnly = true)
    public NotificationPageResponse listMyNotifications(GateKeeperPrincipal principal, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Page<NotificationEntity> res = notificationRepository.findByUserIdOrderByCreatedAtDesc(
                principal.userId(), PageRequest.of(safePage, safeSize));
        long unread = notificationRepository.countByUserIdAndReadFalse(principal.userId());
        List<NotificationResponse> items = res.getContent().stream().map(this::toDto).toList();
        return new NotificationPageResponse(
                items,
                res.getNumber(),
                res.getSize(),
                res.getTotalElements(),
                res.getTotalPages(),
                unread
        );
    }

    @Transactional
    public NotificationResponse markAsRead(UUID notificationId, GateKeeperPrincipal principal) {
        NotificationEntity entity = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        if (!entity.getUserId().equals(principal.userId())) {
            throw new ForbiddenException("You can only manage your own notifications");
        }
        entity.markRead();
        notificationRepository.save(entity);
        return toDto(entity);
    }

    @Transactional
    public void markAllAsRead(GateKeeperPrincipal principal) {
        notificationRepository.markAllReadForUser(principal.userId());
    }

    private NotificationResponse toDto(NotificationEntity n) {
        return new NotificationResponse(
                n.getId(),
                n.getEventId(),
                n.getType(),
                n.getTitle(),
                n.getMessage(),
                n.getReferenceId(),
                n.isRead(),
                n.getCreatedAt()
        );
    }
}

