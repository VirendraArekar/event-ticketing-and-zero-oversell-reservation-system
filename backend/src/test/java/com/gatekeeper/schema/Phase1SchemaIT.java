package com.gatekeeper.schema;

import com.gatekeeper.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Phase1SchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void flywayMigratesAllTablesAndRedisIsReachable() {
        List<String> tables = jdbcTemplate.queryForList("SHOW TABLES", String.class);

        assertThat(tables).contains(
                "users",
                "user_roles",
                "refresh_tokens",
                "events",
                "event_staff_assignments",
                "ticket_types",
                "ticket_inventory",
                "orders",
                "order_items",
                "idempotency_records",
                "payment_ledger",
                "tickets",
                "check_in_logs",
                "waitlist_entries",
                "notifications",
                "outbox_events",
                "processed_kafka_events",
                "event_dashboard_stats",
                "ticket_type_dashboard_stats",
                "event_checkin_buckets",
                "event_sales_buckets"
        );

        String pong = redisTemplate.getConnectionFactory().getConnection().ping();
        assertThat(pong).isEqualTo("PONG");
    }

    @Test
    void inventoryCheckConstraintPreventsOversellAtDatabaseLevel() {
        String userId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String ticketTypeId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp start = Timestamp.from(Instant.now().plusSeconds(3600));
        Timestamp end = Timestamp.from(Instant.now().plusSeconds(7200));

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, TRUE, ?, ?)",
                userId, "check-" + userId + "@gatekeeper.local", "$2a$10$hash", "Constraint User", now, now
        );
        jdbcTemplate.update(
                "INSERT INTO events (id, organizer_id, title, description, category, venue_name, venue_address, venue_capacity, start_time, end_time, timezone, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, userId, "Constraint Event", "Desc", "Tech", "Hall", "123 St", 10, start, end, "UTC", "DRAFT", now, now
        );
        jdbcTemplate.update(
                "INSERT INTO ticket_types (id, event_id, name, price_cents, currency, capacity, per_order_limit, sales_start, sales_end, created_at, updated_at) VALUES (?, ?, ?, ?, 'USD', ?, ?, ?, ?, ?, ?)",
                ticketTypeId, eventId, "GA", 0, 5, 5, now, start, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO ticket_inventory (ticket_type_id, event_id, total, held, sold, updated_at) VALUES (?, ?, ?, 0, 0, ?)",
                ticketTypeId, eventId, 5, now
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ticket_inventory SET held = 6 WHERE ticket_type_id = ?",
                ticketTypeId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_inventory_bounds");
    }
}

