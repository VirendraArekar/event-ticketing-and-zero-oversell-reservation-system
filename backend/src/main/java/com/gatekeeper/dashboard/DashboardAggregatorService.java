package com.gatekeeper.dashboard;

import com.gatekeeper.messaging.DomainEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class DashboardAggregatorService {

    private final JdbcTemplate jdbcTemplate;

    public DashboardAggregatorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void onEventPublished(DomainEvents.EventPublishedPayload payload) {
        jdbcTemplate.update("""
                INSERT INTO event_dashboard_stats (
                    event_id, total_capacity, total_sold, total_revenue_cents, total_checked_in, total_cancelled, updated_at
                ) VALUES (?, ?, 0, 0, 0, 0, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    total_capacity = VALUES(total_capacity),
                    updated_at = UTC_TIMESTAMP(6)
                """,
                payload.eventId().toString(),
                payload.totalCapacity()
        );
        if (payload.ticketTypes() != null) {
            for (DomainEvents.TicketTypeInit tt : payload.ticketTypes()) {
                upsertTicketType(tt.ticketTypeId(), payload.eventId(), tt.name(), tt.capacity());
            }
        }
    }

    @Transactional
    public void onTicketTypeUpsert(DomainEvents.TicketTypeUpsertPayload payload) {
        upsertTicketType(payload.ticketTypeId(), payload.eventId(), payload.name(), payload.capacity());
        jdbcTemplate.update("""
                INSERT INTO event_dashboard_stats (
                    event_id, total_capacity, total_sold, total_revenue_cents, total_checked_in, total_cancelled, updated_at
                )
                SELECT ?, COALESCE(SUM(capacity), 0), 0, 0, 0, 0, UTC_TIMESTAMP(6)
                FROM ticket_type_dashboard_stats WHERE event_id = ?
                ON DUPLICATE KEY UPDATE
                    total_capacity = VALUES(total_capacity),
                    updated_at = UTC_TIMESTAMP(6)
                """,
                payload.eventId().toString(),
                payload.eventId().toString()
        );
    }

    @Transactional
    public void onOrderConfirmed(DomainEvents.OrderConfirmedPayload payload) {
        int totalQty = payload.items().stream().mapToInt(DomainEvents.OrderItemMetric::quantity).sum();

        jdbcTemplate.update("""
                INSERT INTO event_dashboard_stats (
                    event_id, total_capacity, total_sold, total_revenue_cents, total_checked_in, total_cancelled, updated_at
                ) VALUES (?, 0, ?, ?, 0, 0, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    total_sold = total_sold + VALUES(total_sold),
                    total_revenue_cents = total_revenue_cents + VALUES(total_revenue_cents),
                    updated_at = UTC_TIMESTAMP(6)
                """,
                payload.eventId().toString(),
                totalQty,
                payload.totalCents()
        );

        for (DomainEvents.OrderItemMetric item : payload.items()) {
            jdbcTemplate.update("""
                    INSERT INTO ticket_type_dashboard_stats (
                        ticket_type_id, event_id, ticket_type_name, capacity, sold_count, revenue_cents, checked_in_count, updated_at
                    ) VALUES (?, ?, ?, 0, ?, ?, 0, UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE
                        ticket_type_name = VALUES(ticket_type_name),
                        sold_count = sold_count + VALUES(sold_count),
                        revenue_cents = revenue_cents + VALUES(revenue_cents),
                        updated_at = UTC_TIMESTAMP(6)
                    """,
                    item.ticketTypeId().toString(),
                    payload.eventId().toString(),
                    item.ticketTypeName(),
                    item.quantity(),
                    item.lineTotalCents()
            );
        }

        long bucketEpochSec = truncateTo15Minutes(payload.confirmedAt());
        jdbcTemplate.update("""
                INSERT INTO event_sales_buckets (event_id, bucket_start, tickets_sold, revenue_cents)
                VALUES (?, TIMESTAMPADD(SECOND, ?, '1970-01-01 00:00:00'), ?, ?)
                ON DUPLICATE KEY UPDATE
                    tickets_sold = tickets_sold + VALUES(tickets_sold),
                    revenue_cents = revenue_cents + VALUES(revenue_cents)
                """,
                payload.eventId().toString(),
                bucketEpochSec,
                totalQty,
                payload.totalCents()
        );
    }

    @Transactional
    public void onOrderRefunded(DomainEvents.OrderRefundedPayload payload) {
        int totalQty = payload.items().stream().mapToInt(DomainEvents.OrderItemMetric::quantity).sum();

        jdbcTemplate.update("""
                INSERT INTO event_dashboard_stats (
                    event_id, total_capacity, total_sold, total_revenue_cents, total_checked_in, total_cancelled, updated_at
                ) VALUES (?, 0, 0, 0, 0, ?, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    total_sold = GREATEST(0, total_sold - ?),
                    total_revenue_cents = GREATEST(0, total_revenue_cents - ?),
                    total_cancelled = total_cancelled + ?,
                    updated_at = UTC_TIMESTAMP(6)
                """,
                payload.eventId().toString(),
                totalQty,
                totalQty,
                payload.totalCents(),
                totalQty
        );

        for (DomainEvents.OrderItemMetric item : payload.items()) {
            jdbcTemplate.update("""
                    UPDATE ticket_type_dashboard_stats
                    SET sold_count = GREATEST(0, sold_count - ?),
                        revenue_cents = GREATEST(0, revenue_cents - ?),
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE ticket_type_id = ?
                    """,
                    item.quantity(),
                    item.lineTotalCents(),
                    item.ticketTypeId().toString()
            );
        }
    }

    @Transactional
    public void onTicketCheckedIn(DomainEvents.TicketCheckedInPayload payload) {
        jdbcTemplate.update("""
                INSERT INTO event_dashboard_stats (
                    event_id, total_capacity, total_sold, total_revenue_cents, total_checked_in, total_cancelled, updated_at
                ) VALUES (?, 0, 0, 0, 1, 0, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    total_checked_in = total_checked_in + 1,
                    updated_at = UTC_TIMESTAMP(6)
                """,
                payload.eventId().toString()
        );

        jdbcTemplate.update("""
                UPDATE ticket_type_dashboard_stats
                SET checked_in_count = checked_in_count + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE ticket_type_id = ?
                """,
                payload.ticketTypeId().toString()
        );

        long bucketEpochSec = truncateTo15Minutes(payload.checkedInAt());
        jdbcTemplate.update("""
                INSERT INTO event_checkin_buckets (event_id, bucket_start, checkin_count)
                VALUES (?, TIMESTAMPADD(SECOND, ?, '1970-01-01 00:00:00'), 1)
                ON DUPLICATE KEY UPDATE
                    checkin_count = checkin_count + 1
                """,
                payload.eventId().toString(),
                bucketEpochSec
        );
    }

    private void upsertTicketType(UUID ticketTypeId, UUID eventId, String name, int capacity) {
        jdbcTemplate.update("""
                INSERT INTO ticket_type_dashboard_stats (
                    ticket_type_id, event_id, ticket_type_name, capacity, sold_count, revenue_cents, checked_in_count, updated_at
                ) VALUES (?, ?, ?, ?, 0, 0, 0, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    ticket_type_name = VALUES(ticket_type_name),
                    capacity = VALUES(capacity),
                    updated_at = UTC_TIMESTAMP(6)
                """,
                ticketTypeId.toString(),
                eventId.toString(),
                name,
                capacity
        );
    }

    private static long truncateTo15Minutes(Instant timestamp) {
        long epochSec = timestamp.getEpochSecond();
        return (epochSec / 900L) * 900L;
    }
}

