package com.gatekeeper.order;

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
@Table(name = "orders")
public class OrderEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "payment_deadline_at")
    private Instant paymentDeadlineAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderEntity() {
    }

    public OrderEntity(
            UUID id,
            UUID userId,
            UUID eventId,
            OrderStatus status,
            long totalCents,
            String currency,
            String idempotencyKey,
            Instant holdExpiresAt,
            Instant now
    ) {
        this.id = id;
        this.userId = userId;
        this.eventId = eventId;
        this.status = status;
        this.totalCents = totalCents;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.holdExpiresAt = holdExpiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Instant getPaymentDeadlineAt() {
        return paymentDeadlineAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
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

    public void markPendingPayment(Instant paymentDeadlineAt, Instant now) {
        this.status = OrderStatus.PENDING_PAYMENT;
        this.holdExpiresAt = null;
        this.paymentDeadlineAt = paymentDeadlineAt;
        this.updatedAt = now;
    }

    public void markConfirmed(Instant now) {
        this.status = OrderStatus.CONFIRMED;
        this.holdExpiresAt = null;
        this.paymentDeadlineAt = null;
        this.confirmedAt = now;
        this.updatedAt = now;
    }

    public void markCancelled(OrderStatus targetStatus, Instant now) {
        this.status = targetStatus;
        this.holdExpiresAt = null;
        this.paymentDeadlineAt = null;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    public void markExpired(Instant now) {
        this.status = OrderStatus.EXPIRED;
        this.holdExpiresAt = null;
        this.paymentDeadlineAt = null;
        this.updatedAt = now;
    }
}

