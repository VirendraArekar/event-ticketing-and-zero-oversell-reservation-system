package com.gatekeeper.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EventDtos {

    private EventDtos() {
    }

    public record CreateOrUpdateEventRequest(
            @NotBlank(message = "Title is required")
            @Size(max = 200, message = "Title must not exceed 200 characters")
            String title,

            @NotBlank(message = "Description is required")
            @Size(max = 10000, message = "Description must not exceed 10000 characters")
            String description,

            @NotBlank(message = "Category is required")
            @Size(max = 80, message = "Category must not exceed 80 characters")
            String category,

            @NotBlank(message = "Venue name is required")
            @Size(max = 200, message = "Venue name must not exceed 200 characters")
            String venueName,

            @NotBlank(message = "Venue address is required")
            @Size(max = 300, message = "Venue address must not exceed 300 characters")
            String venueAddress,

            @Min(value = 1, message = "Venue capacity must be at least 1")
            @Max(value = 1_000_000, message = "Venue capacity exceeds maximum")
            int venueCapacity,

            @NotNull(message = "Start time is required")
            Instant startTime,

            @NotNull(message = "End time is required")
            Instant endTime,

            @NotBlank(message = "Timezone is required")
            @Size(max = 64)
            String timezone,

            Instant cancellationCutoffAt,

            Boolean waitingRoomEnabled,

            @Min(value = 1, message = "Waiting room threshold must be at least 1")
            Integer waitingRoomThreshold
    ) {
    }

    public record CreateOrUpdateTicketTypeRequest(
            @NotBlank(message = "Ticket type name is required")
            @Size(max = 120, message = "Ticket type name must not exceed 120 characters")
            String name,

            @Size(max = 500, message = "Description must not exceed 500 characters")
            String description,

            @Min(value = 0, message = "Price cannot be negative")
            long priceCents,

            @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
            String currency,

            @Min(value = 1, message = "Capacity must be at least 1")
            @Max(value = 1_000_000)
            int capacity,

            @Min(value = 1, message = "Per-order limit must be at least 1")
            @Max(value = 100)
            int perOrderLimit,

            @NotNull(message = "Sales start is required")
            Instant salesStart,

            @NotNull(message = "Sales end is required")
            Instant salesEnd
    ) {
    }

    public record AssignStaffRequest(
            @NotBlank(message = "Staff user email is required")
            String email
    ) {
    }

    public record TicketTypeResponse(
            UUID id,
            UUID eventId,
            String name,
            String description,
            long priceCents,
            String currency,
            int capacity,
            int held,
            int sold,
            int available,
            int perOrderLimit,
            Instant salesStart,
            Instant salesEnd
    ) {
    }

    public record EventSummaryResponse(
            UUID id,
            UUID organizerId,
            String title,
            String category,
            String venueName,
            String venueAddress,
            int venueCapacity,
            Instant startTime,
            Instant endTime,
            String timezone,
            EventStatus status,
            boolean waitingRoomEnabled,
            long minPriceCents,
            int totalAvailable
    ) {
    }

    public record EventDetailResponse(
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
            EventStatus status,
            Instant cancellationCutoffAt,
            boolean waitingRoomEnabled,
            int waitingRoomThreshold,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<TicketTypeResponse> ticketTypes
    ) {
    }

    public record StaffAssignmentResponse(
            UUID id,
            UUID eventId,
            UUID userId,
            String email,
            String fullName,
            Instant assignedAt
    ) {
    }
}

