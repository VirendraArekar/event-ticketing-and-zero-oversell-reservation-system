package com.gatekeeper.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_staff_assignments")
public class EventStaffAssignmentEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "assigned_by", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID assignedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EventStaffAssignmentEntity() {
    }

    public EventStaffAssignmentEntity(UUID id, UUID eventId, UUID userId, UUID assignedBy, Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.userId = userId;
        this.assignedBy = assignedBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

