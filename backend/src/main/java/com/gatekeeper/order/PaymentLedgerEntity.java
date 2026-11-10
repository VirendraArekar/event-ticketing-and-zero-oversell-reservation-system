package com.gatekeeper.order;

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
@Table(name = "payment_ledger")
public class PaymentLedgerEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID orderId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private LedgerEntryType entryType;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "reference_code", length = 128)
    private String referenceCode;

    @Column(name = "notes", length = 500)
    private String notes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "actor_user_id", length = 36, columnDefinition = "CHAR(36)")
    private UUID actorUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentLedgerEntity() {
    }

    public PaymentLedgerEntity(
            UUID id,
            UUID orderId,
            UUID eventId,
            String provider,
            LedgerEntryType entryType,
            long amountCents,
            String currency,
            String referenceCode,
            String notes,
            UUID actorUserId,
            Instant createdAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.eventId = eventId;
        this.provider = provider;
        this.entryType = entryType;
        this.amountCents = amountCents;
        this.currency = currency;
        this.referenceCode = referenceCode;
        this.notes = notes;
        this.actorUserId = actorUserId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getProvider() {
        return provider;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReferenceCode() {
        return referenceCode;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

