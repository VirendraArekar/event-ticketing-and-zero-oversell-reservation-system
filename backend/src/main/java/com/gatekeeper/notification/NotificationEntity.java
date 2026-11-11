package com.gatekeeper.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reference_id", length = 36, columnDefinition = "CHAR(36)")
    private UUID referenceId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationEntity() {
    }

    public NotificationEntity(
            UUID id,
            UUID userId,
            UUID eventId,
            NotificationType type,
            String title,
            String message,
            UUID referenceId,
            Instant createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.eventId = eventId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.referenceId = referenceId;
        this.read = false;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markRead() {
        this.read = true;
    }
}

