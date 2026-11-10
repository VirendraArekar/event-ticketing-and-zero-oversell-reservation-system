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
@Table(name = "ticket_inventory")
public class TicketInventoryEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ticket_type_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID ticketTypeId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @Column(name = "total", nullable = false)
    private int total;

    @Column(name = "held", nullable = false)
    private int held;

    @Column(name = "sold", nullable = false)
    private int sold;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketInventoryEntity() {
    }

    public TicketInventoryEntity(UUID ticketTypeId, UUID eventId, int total, Instant now) {
        this.ticketTypeId = ticketTypeId;
        this.eventId = eventId;
        this.total = total;
        this.held = 0;
        this.sold = 0;
        this.updatedAt = now;
    }

    public UUID getTicketTypeId() {
        return ticketTypeId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public int getTotal() {
        return total;
    }

    public int getHeld() {
        return held;
    }

    public int getSold() {
        return sold;
    }

    public int getAvailable() {
        return Math.max(0, total - held - sold);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setTotal(int total, Instant now) {
        this.total = total;
        this.updatedAt = now;
    }
}

