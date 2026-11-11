package com.gatekeeper.waitlist;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class WaitlistDtos {

    private WaitlistDtos() {
    }

    public record JoinWaitlistRequest(
            @NotNull(message = "Ticket type ID is required")
            UUID ticketTypeId,

            @Min(value = 1, message = "Quantity must be at least 1")
            @Max(value = 100, message = "Quantity cannot exceed 100")
            int quantity
    ) {
    }

    public record WaitlistEntryResponse(
            UUID id,
            UUID eventId,
            String eventTitle,
            UUID ticketTypeId,
            String ticketTypeName,
            UUID userId,
            int quantity,
            WaitlistStatus status,
            long position,
            UUID offerOrderId,
            Instant offeredAt,
            Instant offerExpiresAt,
            Instant createdAt
    ) {
    }
}

