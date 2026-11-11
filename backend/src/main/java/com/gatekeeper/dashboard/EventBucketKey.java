package com.gatekeeper.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class EventBucketKey implements Serializable {

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    protected EventBucketKey() {
    }

    public EventBucketKey(UUID eventId, Instant bucketStart) {
        this.eventId = eventId;
        this.bucketStart = bucketStart;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getBucketStart() {
        return bucketStart;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventBucketKey that)) return false;
        return Objects.equals(eventId, that.eventId) && Objects.equals(bucketStart, that.bucketStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, bucketStart);
    }
}

