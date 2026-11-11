package com.gatekeeper.waitlist;

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
@Table(name = "waitlist_entries")
public class WaitlistEntryEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ticket_type_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID ticketTypeId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID userId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WaitlistStatus status;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "offer_order_id", length = 36, columnDefinition = "CHAR(36)")
    private UUID offerOrderId;

    @Column(name = "offered_at")
    private Instant offeredAt;

    @Column(name = "offer_expires_at")
    private Instant offerExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WaitlistEntryEntity() {
    }

    public WaitlistEntryEntity(
            UUID id,
            UUID eventId,
            UUID ticketTypeId,
            UUID userId,
            int quantity,
            Instant now
    ) {
        this.id = id;
        this.eventId = eventId;
        this.ticketTypeId = ticketTypeId;
        this.userId = userId;
        this.quantity = quantity;
        this.status = WaitlistStatus.WAITING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getTicketTypeId() {
        return ticketTypeId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getQuantity() {
        return quantity;
    }

    public WaitlistStatus getStatus() {
        return status;
    }

    public UUID getOfferOrderId() {
        return offerOrderId;
    }

    public Instant getOfferedAt() {
        return offeredAt;
    }

    public Instant getOfferExpiresAt() {
        return offerExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markOffered(UUID orderId, Instant offeredAt, Instant expiresAt) {
        this.status = WaitlistStatus.OFFERED;
        this.offerOrderId = orderId;
        this.offeredAt = offeredAt;
        this.offerExpiresAt = expiresAt;
        this.updatedAt = offeredAt;
    }

    public void transitionStatus(WaitlistStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }
}

