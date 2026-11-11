package com.gatekeeper.checkin;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class CheckInDtos {

    private CheckInDtos() {
    }

    public record CheckInRequest(
            @Size(max = 512)
            String qrToken,

            @Size(max = 64)
            String ticketCode
    ) {
    }

    public record CheckInResponse(
            UUID ticketId,
            UUID eventId,
            String ticketCode,
            String ticketTypeName,
            String attendeeName,
            String status,
            Instant checkedInAt,
            UUID checkedInBy,
            String checkedInByName
    ) {
    }

    public record CheckInLogResponse(
            UUID id,
            UUID ticketId,
            UUID eventId,
            UUID staffUserId,
            CheckInOutcome outcome,
            Instant scannedAt,
            String notes
    ) {
    }
}

