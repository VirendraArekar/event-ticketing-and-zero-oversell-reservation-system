package com.gatekeeper.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record HoldItemRequest(
            @NotNull(message = "Ticket type ID is required")
            UUID ticketTypeId,

            @Min(value = 1, message = "Quantity must be at least 1")
            @Max(value = 100, message = "Quantity cannot exceed 100")
            int quantity
    ) {
    }

    public record PlaceHoldRequest(
            @NotNull(message = "Event ID is required")
            UUID eventId,

            @NotEmpty(message = "At least one ticket item is required")
            @Size(max = 20, message = "Cannot book more than 20 ticket types in one order")
            List<@Valid HoldItemRequest> items
    ) {
    }

    public record MarkPaidRequest(
            @Size(max = 128)
            String referenceCode,

            @Size(max = 500)
            String notes
    ) {
    }

    public record OrderItemResponse(
            UUID id,
            UUID ticketTypeId,
            String ticketTypeName,
            int quantity,
            long unitPriceCents,
            long lineTotalCents
    ) {
    }

    public record LedgerEntryResponse(
            UUID id,
            String provider,
            LedgerEntryType entryType,
            long amountCents,
            String currency,
            String referenceCode,
            String notes,
            UUID actorUserId,
            Instant createdAt
    ) {
    }

    public record OrderResponse(
            UUID id,
            UUID userId,
            UUID eventId,
            String eventTitle,
            OrderStatus status,
            long totalCents,
            String currency,
            Instant holdExpiresAt,
            Instant paymentDeadlineAt,
            Instant confirmedAt,
            Instant cancelledAt,
            Instant createdAt,
            List<OrderItemResponse> items,
            List<LedgerEntryResponse> ledger
    ) {
    }
}

