package com.gatekeeper.ticket;

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
@Table(name = "tickets")
public class TicketEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID orderId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_item_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID orderItemId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ticket_type_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID ticketTypeId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID userId;

    @Column(name = "ticket_code", nullable = false, unique = true, length = 64)
    private String ticketCode;

    @Column(name = "qr_token", nullable = false, length = 512)
    private String qrToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TicketStatus status;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checked_in_by", length = 36, columnDefinition = "CHAR(36)")
    private UUID checkedInBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketEntity() {
    }

    public TicketEntity(
            UUID id,
            UUID orderId,
            UUID orderItemId,
            UUID eventId,
            UUID ticketTypeId,
            UUID userId,
            String ticketCode,
            String qrToken,
            Instant now
    ) {
        this.id = id;
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.eventId = eventId;
        this.ticketTypeId = ticketTypeId;
        this.userId = userId;
        this.ticketCode = ticketCode;
        this.qrToken = qrToken;
        this.status = TicketStatus.VALID;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getOrderItemId() {
        return orderItemId;
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

    public String getTicketCode() {
        return ticketCode;
    }

    public String getQrToken() {
        return qrToken;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public UUID getCheckedInBy() {
        return checkedInBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

