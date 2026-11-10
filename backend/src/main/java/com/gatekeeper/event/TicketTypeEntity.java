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
@Table(name = "ticket_types")
public class TicketTypeEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "per_order_limit", nullable = false)
    private int perOrderLimit;

    @Column(name = "sales_start", nullable = false)
    private Instant salesStart;

    @Column(name = "sales_end", nullable = false)
    private Instant salesEnd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketTypeEntity() {
    }

    public TicketTypeEntity(
            UUID id,
            UUID eventId,
            String name,
            String description,
            long priceCents,
            String currency,
            int capacity,
            int perOrderLimit,
            Instant salesStart,
            Instant salesEnd,
            Instant now
    ) {
        this.id = id;
        this.eventId = eventId;
        this.name = name;
        this.description = description;
        this.priceCents = priceCents;
        this.currency = currency;
        this.capacity = capacity;
        this.perOrderLimit = perOrderLimit;
        this.salesStart = salesStart;
        this.salesEnd = salesEnd;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public String getCurrency() {
        return currency;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getPerOrderLimit() {
        return perOrderLimit;
    }

    public Instant getSalesStart() {
        return salesStart;
    }

    public Instant getSalesEnd() {
        return salesEnd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(
            String name,
            String description,
            long priceCents,
            int capacity,
            int perOrderLimit,
            Instant salesStart,
            Instant salesEnd,
            Instant now
    ) {
        this.name = name;
        this.description = description;
        this.priceCents = priceCents;
        this.capacity = capacity;
        this.perOrderLimit = perOrderLimit;
        this.salesStart = salesStart;
        this.salesEnd = salesEnd;
        this.updatedAt = now;
    }
}

