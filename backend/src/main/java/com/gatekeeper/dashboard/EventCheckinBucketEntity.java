package com.gatekeeper.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "event_checkin_buckets")
public class EventCheckinBucketEntity {

    @EmbeddedId
    private EventBucketKey id;

    @Column(name = "checkin_count", nullable = false)
    private int checkinCount;

    protected EventCheckinBucketEntity() {
    }

    public EventBucketKey getId() {
        return id;
    }

    public int getCheckinCount() {
        return checkinCount;
    }
}

