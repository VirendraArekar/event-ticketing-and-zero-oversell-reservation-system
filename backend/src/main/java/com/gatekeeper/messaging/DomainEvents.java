package com.gatekeeper.messaging;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DomainEvents {

    private DomainEvents() {
    }

    public record Envelope(
            UUID messageId,
            String eventType,
            UUID eventId,
            Instant occurredAt,
            JsonNode data
    ) {
    }

    public record TicketTypeInit(UUID ticketTypeId, String name, int capacity) {
    }

    public record EventPublishedPayload(
            UUID eventId,
            String title,
            int totalCapacity,
            List<TicketTypeInit> ticketTypes
    ) {
    }

    public record TicketTypeUpsertPayload(
            UUID eventId,
            UUID ticketTypeId,
            String name,
            int capacity
    ) {
    }

    public record EventUpdatedOrCancelledPayload(
            UUID eventId,
            String title,
            boolean cancelled
    ) {
    }

    public record OrderItemMetric(
            UUID ticketTypeId,
            String ticketTypeName,
            int quantity,
            long lineTotalCents
    ) {
    }

    public record OrderConfirmedPayload(
            UUID orderId,
            UUID userId,
            UUID eventId,
            String eventTitle,
            long totalCents,
            String currency,
            Instant confirmedAt,
            List<OrderItemMetric> items
    ) {
    }

    public record OrderPendingPaymentPayload(
            UUID orderId,
            UUID userId,
            UUID eventId,
            String eventTitle,
            long totalCents,
            String currency,
            Instant paymentDeadlineAt
    ) {
    }

    public record OrderRefundedPayload(
            UUID orderId,
            UUID userId,
            UUID eventId,
            String eventTitle,
            long totalCents,
            List<OrderItemMetric> items
    ) {
    }

    public record HoldExpiringPayload(
            UUID orderId,
            UUID userId,
            UUID eventId,
            String eventTitle,
            Instant holdExpiresAt
    ) {
    }

    public record TicketsIssuedPayload(
            UUID orderId,
            UUID userId,
            UUID eventId,
            List<UUID> ticketIds
    ) {
    }

    public record TicketCheckedInPayload(
            UUID ticketId,
            UUID eventId,
            UUID ticketTypeId,
            UUID userId,
            String eventTitle,
            String ticketCode,
            Instant checkedInAt
    ) {
    }

    public record SeatsReleasedPayload(
            UUID eventId,
            List<UUID> ticketTypeIds,
            String reason
    ) {
    }

    public record WaitlistOfferedPayload(
            UUID waitlistEntryId,
            UUID eventId,
            UUID ticketTypeId,
            String ticketTypeName,
            UUID userId,
            UUID offerOrderId,
            int quantity,
            Instant offerExpiresAt
    ) {
    }
}

