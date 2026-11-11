package com.gatekeeper.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_type_dashboard_stats")
public class TicketTypeDashboardStatsEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ticket_type_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID ticketTypeId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @Column(name = "ticket_type_name", nullable = false, length = 120)
    private String ticketTypeName;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "sold_count", nullable = false)
    private int soldCount;

    @Column(name = "revenue_cents", nullable = false)
    private long revenueCents;

    @Column(name = "checked_in_count", nullable = false)
    private int checkedInCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketTypeDashboardStatsEntity() {
    }

    public UUID getTicketTypeId() {
        return ticketTypeId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getTicketTypeName() {
        return ticketTypeName;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getSoldCount() {
        return soldCount;
    }

    public long getRevenueCents() {
        return revenueCents;
    }

    public int getCheckedInCount() {
        return checkedInCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

