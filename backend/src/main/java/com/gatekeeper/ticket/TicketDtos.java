package com.gatekeeper.ticket;

import java.time.Instant;
import java.util.UUID;

public final class TicketDtos {

    private TicketDtos() {
    }

    public record TicketResponse(
            UUID id,
            UUID orderId,
            UUID eventId,
            String eventTitle,
            String venueName,
            String venueAddress,
            Instant eventStartTime,
            Instant eventEndTime,
            String timezone,
            UUID ticketTypeId,
            String ticketTypeName,
            UUID userId,
            String attendeeName,
            String ticketCode,
            String qrToken,
            TicketStatus status,
            Instant checkedInAt,
            UUID checkedInBy,
            Instant createdAt
    ) {
    }
}

