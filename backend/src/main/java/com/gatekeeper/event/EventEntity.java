package com.gatekeeper.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class EventEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "organizer_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID organizerId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "category", nullable = false, length = 80)
    private String category;

    @Column(name = "venue_name", nullable = false, length = 200)
    private String venueName;

    @Column(name = "venue_address", nullable = false, length = 300)
    private String venueAddress;

    @Column(name = "venue_capacity", nullable = false)
    private int venueCapacity;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private EventStatus status;

    @Column(name = "cancellation_cutoff_at")
    private Instant cancellationCutoffAt;

    @Column(name = "waiting_room_enabled", nullable = false)
    private boolean waitingRoomEnabled;

    @Column(name = "waiting_room_threshold", nullable = false)
    private int waitingRoomThreshold = 25;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventEntity() {
    }

    public EventEntity(
            UUID id,
            UUID organizerId,
            String title,
            String description,
            String category,
            String venueName,
            String venueAddress,
            int venueCapacity,
            Instant startTime,
            Instant endTime,
            String timezone,
            Instant cancellationCutoffAt,
            boolean waitingRoomEnabled,
            int waitingRoomThreshold,
            Instant now
    ) {
        this.id = id;
        this.organizerId = organizerId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.venueName = venueName;
        this.venueAddress = venueAddress;
        this.venueCapacity = venueCapacity;
        this.startTime = startTime;
        this.endTime = endTime;
        this.timezone = timezone;
        this.status = EventStatus.DRAFT;
        this.cancellationCutoffAt = cancellationCutoffAt;
        this.waitingRoomEnabled = waitingRoomEnabled;
        this.waitingRoomThreshold = waitingRoomThreshold;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizerId() {
        return organizerId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public String getVenueName() {
        return venueName;
    }

    public String getVenueAddress() {
        return venueAddress;
    }

    public int getVenueCapacity() {
        return venueCapacity;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public String getTimezone() {
        return timezone;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getCancellationCutoffAt() {
        return cancellationCutoffAt;
    }

    public boolean isWaitingRoomEnabled() {
        return waitingRoomEnabled;
    }

    public int getWaitingRoomThreshold() {
        return waitingRoomThreshold;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateDetails(
            String title,
            String description,
            String category,
            String venueName,
            String venueAddress,
            int venueCapacity,
            Instant startTime,
            Instant endTime,
            String timezone,
            Instant cancellationCutoffAt,
            boolean waitingRoomEnabled,
            int waitingRoomThreshold,
            Instant now
    ) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.venueName = venueName;
        this.venueAddress = venueAddress;
        this.venueCapacity = venueCapacity;
        this.startTime = startTime;
        this.endTime = endTime;
        this.timezone = timezone;
        this.cancellationCutoffAt = cancellationCutoffAt;
        this.waitingRoomEnabled = waitingRoomEnabled;
        this.waitingRoomThreshold = waitingRoomThreshold;
        this.updatedAt = now;
    }

    public void transitionStatus(EventStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }
}

