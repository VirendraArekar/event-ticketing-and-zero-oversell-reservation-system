package com.gatekeeper.dashboard;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.UserEntity;
import com.gatekeeper.auth.UserRepository;
import com.gatekeeper.common.web.PageResponse;
import com.gatekeeper.event.EventAuthorizationService;
import com.gatekeeper.event.EventEntity;
import com.gatekeeper.event.TicketTypeEntity;
import com.gatekeeper.event.TicketTypeRepository;
import com.gatekeeper.ticket.TicketEntity;
import com.gatekeeper.ticket.TicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private final EventAuthorizationService authorizationService;
    private final EventDashboardStatsRepository eventStatsRepository;
    private final TicketTypeDashboardStatsRepository ticketTypeStatsRepository;
    private final EventCheckinBucketRepository checkinBucketRepository;
    private final EventSalesBucketRepository salesBucketRepository;
    private final TicketRepository ticketRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final UserRepository userRepository;

    public DashboardService(
            EventAuthorizationService authorizationService,
            EventDashboardStatsRepository eventStatsRepository,
            TicketTypeDashboardStatsRepository ticketTypeStatsRepository,
            EventCheckinBucketRepository checkinBucketRepository,
            EventSalesBucketRepository salesBucketRepository,
            TicketRepository ticketRepository,
            TicketTypeRepository ticketTypeRepository,
            UserRepository userRepository
    ) {
        this.authorizationService = authorizationService;
        this.eventStatsRepository = eventStatsRepository;
        this.ticketTypeStatsRepository = ticketTypeStatsRepository;
        this.checkinBucketRepository = checkinBucketRepository;
        this.salesBucketRepository = salesBucketRepository;
        this.ticketRepository = ticketRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public DashboardDtos.DashboardResponse getDashboard(UUID eventId, GateKeeperPrincipal principal) {
        EventEntity event = authorizationService.requireEventOwnedByOrganizer(eventId, principal);

        EventDashboardStatsEntity stats = eventStatsRepository.findById(eventId).orElse(null);
        List<TicketTypeDashboardStatsEntity> typeStats =
                ticketTypeStatsRepository.findByEventIdOrderBySoldCountDescRevenueCentsDesc(eventId);
        List<EventCheckinBucketEntity> checkinBuckets =
                checkinBucketRepository.findByIdEventIdOrderByIdBucketStartAsc(eventId);
        List<EventSalesBucketEntity> salesBuckets =
                salesBucketRepository.findByIdEventIdOrderByIdBucketStartAsc(eventId);

        int totalCapacity = stats != null && stats.getTotalCapacity() > 0
                ? stats.getTotalCapacity()
                : typeStats.stream().mapToInt(TicketTypeDashboardStatsEntity::getCapacity).sum();
        int totalSold = stats != null ? stats.getTotalSold() : 0;
        long totalRevenueCents = stats != null ? stats.getTotalRevenueCents() : 0L;
        int totalCheckedIn = stats != null ? stats.getTotalCheckedIn() : 0;
        int totalCancelled = stats != null ? stats.getTotalCancelled() : 0;

        int divisorCap = totalCapacity > 0 ? totalCapacity : event.getVenueCapacity();
        double capacityUsedPct = divisorCap > 0
                ? Math.round((totalSold * 10000.0) / divisorCap) / 100.0
                : 0.0;
        double checkInRatePct = totalSold > 0
                ? Math.round((totalCheckedIn * 10000.0) / totalSold) / 100.0
                : 0.0;
        int noShowCount = Math.max(0, totalSold - totalCheckedIn);

        List<DashboardDtos.TicketTypeMetric> typeMetrics = typeStats.stream()
                .map(t -> new DashboardDtos.TicketTypeMetric(
                        t.getTicketTypeId(),
                        t.getTicketTypeName(),
                        t.getCapacity(),
                        t.getSoldCount(),
                        t.getRevenueCents(),
                        t.getCheckedInCount()
                ))
                .toList();

        List<DashboardDtos.TicketTypeMetric> topTypes = typeMetrics.stream().limit(5).toList();

        List<DashboardDtos.CheckInBucketPoint> checkInCurve = checkinBuckets.stream()
                .map(b -> new DashboardDtos.CheckInBucketPoint(b.getId().getBucketStart(), b.getCheckinCount()))
                .toList();

        List<DashboardDtos.SalesBucketPoint> salesOverTime = salesBuckets.stream()
                .map(b -> new DashboardDtos.SalesBucketPoint(
                        b.getId().getBucketStart(), b.getTicketsSold(), b.getRevenueCents()))
                .toList();

        return new DashboardDtos.DashboardResponse(
                event.getId(),
                event.getTitle(),
                event.getStatus(),
                event.getVenueCapacity(),
                totalCapacity,
                totalSold,
                totalRevenueCents,
                capacityUsedPct,
                totalCheckedIn,
                checkInRatePct,
                noShowCount,
                totalCancelled,
                typeMetrics,
                topTypes,
                checkInCurve,
                salesOverTime
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<DashboardDtos.AttendeeRecord> listAttendees(
            UUID eventId,
            GateKeeperPrincipal principal,
            int page,
            int size
    ) {
        authorizationService.requireEventOwnedByOrganizer(eventId, principal);
        Page<TicketEntity> tickets = ticketRepository.findByEventIdOrderByCreatedAtDesc(
                eventId, PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100)));

        Map<UUID, TicketTypeEntity> typeMap = ticketTypeRepository
                .findByEventIdOrderByPriceCentsAscCreatedAtAsc(eventId)
                .stream().collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));
        Map<UUID, UserEntity> userMap = userRepository.findAllById(
                        tickets.getContent().stream().map(TicketEntity::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));

        return PageResponse.from(tickets.map(t -> {
            TicketTypeEntity tt = typeMap.get(t.getTicketTypeId());
            UserEntity u = userMap.get(t.getUserId());
            return new DashboardDtos.AttendeeRecord(
                    t.getId(),
                    t.getTicketCode(),
                    t.getTicketTypeId(),
                    tt != null ? tt.getName() : "",
                    t.getUserId(),
                    u != null ? u.getFullName() : "",
                    u != null ? u.getEmail() : "",
                    t.getStatus(),
                    t.getCheckedInAt(),
                    t.getCreatedAt()
            );
        }));
    }

    @Transactional(readOnly = true)
    public byte[] exportAttendeesCsv(UUID eventId, GateKeeperPrincipal principal) {
        authorizationService.requireEventOwnedByOrganizer(eventId, principal);

        Map<UUID, TicketTypeEntity> typeMap = ticketTypeRepository
                .findByEventIdOrderByPriceCentsAscCreatedAtAsc(eventId)
                .stream().collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));

        StringBuilder csv = new StringBuilder();
        csv.append("ticket_code,ticket_type,attendee_name,attendee_email,status,checked_in_at,issued_at\n");

        int page = 0;
        Page<TicketEntity> batch;
        do {
            batch = ticketRepository.findByEventIdOrderByCreatedAtDesc(eventId, PageRequest.of(page, 200));
            Map<UUID, UserEntity> userMap = userRepository.findAllById(
                            batch.getContent().stream().map(TicketEntity::getUserId).collect(Collectors.toSet()))
                    .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
            for (TicketEntity t : batch.getContent()) {
                TicketTypeEntity tt = typeMap.get(t.getTicketTypeId());
                UserEntity u = userMap.get(t.getUserId());
                csv.append(escapeCsv(t.getTicketCode())).append(',')
                        .append(escapeCsv(tt != null ? tt.getName() : "")).append(',')
                        .append(escapeCsv(u != null ? u.getFullName() : "")).append(',')
                        .append(escapeCsv(u != null ? u.getEmail() : "")).append(',')
                        .append(escapeCsv(t.getStatus().name())).append(',')
                        .append(escapeCsv(t.getCheckedInAt() != null ? t.getCheckedInAt().toString() : "")).append(',')
                        .append(escapeCsv(t.getCreatedAt().toString()))
                        .append('\n');
            }
            page++;
        } while (batch.hasNext());

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeCsv(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw;
        if (!sanitized.isEmpty()) {
            char first = sanitized.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                sanitized = "'" + sanitized;
            }
        }
        if (sanitized.contains(",") || sanitized.contains("\"") || sanitized.contains("\n") || sanitized.contains("\r")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }
}

