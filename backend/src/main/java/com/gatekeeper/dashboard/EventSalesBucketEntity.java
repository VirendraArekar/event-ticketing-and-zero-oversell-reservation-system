package com.gatekeeper.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "event_sales_buckets")
public class EventSalesBucketEntity {

    @EmbeddedId
    private EventBucketKey id;

    @Column(name = "tickets_sold", nullable = false)
    private int ticketsSold;

    @Column(name = "revenue_cents", nullable = false)
    private long revenueCents;

    protected EventSalesBucketEntity() {
    }

    public EventBucketKey getId() {
        return id;
    }

    public int getTicketsSold() {
        return ticketsSold;
    }

    public long getRevenueCents() {
        return revenueCents;
    }
}

