package com.gatekeeper.checkin;

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
@Table(name = "check_in_logs")
public class CheckInLogEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ticket_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID ticketId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "staff_user_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID staffUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private CheckInOutcome outcome;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    @Column(name = "notes", length = 255)
    private String notes;

    protected CheckInLogEntity() {
    }

    public CheckInLogEntity(
            UUID id,
            UUID ticketId,
            UUID eventId,
            UUID staffUserId,
            CheckInOutcome outcome,
            Instant scannedAt,
            String notes
    ) {
        this.id = id;
        this.ticketId = ticketId;
        this.eventId = eventId;
        this.staffUserId = staffUserId;
        this.outcome = outcome;
        this.scannedAt = scannedAt;
        this.notes = notes;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getStaffUserId() {
        return staffUserId;
    }

    public CheckInOutcome getOutcome() {
        return outcome;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public String getNotes() {
        return notes;
    }
}

