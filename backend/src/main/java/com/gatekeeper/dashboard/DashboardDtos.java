package com.gatekeeper.dashboard;

import com.gatekeeper.event.EventStatus;
import com.gatekeeper.ticket.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record TicketTypeMetric(
            UUID ticketTypeId,
            String name,
            int capacity,
            int soldCount,
            long revenueCents,
            int checkedInCount
    ) {
    }

    public record CheckInBucketPoint(
            Instant bucketStart,
            int checkInCount
    ) {
    }

    public record SalesBucketPoint(
            Instant bucketStart,
            int ticketsSold,
            long revenueCents
    ) {
    }

    public record DashboardResponse(
            UUID eventId,
            String eventTitle,
            EventStatus status,
            int venueCapacity,
            int totalTicketCapacity,
            int totalSold,
            long totalRevenueCents,
            double capacityUsedPercent,
            int totalCheckedIn,
            double checkInRatePercent,
            int noShowCount,
            int totalCancelled,
            List<TicketTypeMetric> ticketTypes,
            List<TicketTypeMetric> topTicketTypes,
            List<CheckInBucketPoint> checkInCurve,
            List<SalesBucketPoint> salesOverTime
    ) {
    }

    public record AttendeeRecord(
            UUID ticketId,
            String ticketCode,
            UUID ticketTypeId,
            String ticketTypeName,
            UUID userId,
            String attendeeName,
            String attendeeEmail,
            TicketStatus status,
            Instant checkedInAt,
            Instant issuedAt
    ) {
    }
}

