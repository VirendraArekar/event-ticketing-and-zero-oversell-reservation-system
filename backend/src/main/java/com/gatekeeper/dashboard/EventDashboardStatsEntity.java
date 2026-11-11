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
@Table(name = "event_dashboard_stats")
public class EventDashboardStatsEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @Column(name = "total_capacity", nullable = false)
    private int totalCapacity;

    @Column(name = "total_sold", nullable = false)
    private int totalSold;

    @Column(name = "total_revenue_cents", nullable = false)
    private long totalRevenueCents;

    @Column(name = "total_checked_in", nullable = false)
    private int totalCheckedIn;

    @Column(name = "total_cancelled", nullable = false)
    private int totalCancelled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventDashboardStatsEntity() {
    }

    public UUID getEventId() {
        return eventId;
    }

    public int getTotalCapacity() {
        return totalCapacity;
    }

    public int getTotalSold() {
        return totalSold;
    }

    public long getTotalRevenueCents() {
        return totalRevenueCents;
    }

    public int getTotalCheckedIn() {
        return totalCheckedIn;
    }

    public int getTotalCancelled() {
        return totalCancelled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

