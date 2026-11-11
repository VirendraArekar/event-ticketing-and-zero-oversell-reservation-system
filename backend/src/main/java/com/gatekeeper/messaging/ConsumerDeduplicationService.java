package com.gatekeeper.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ConsumerDeduplicationService {

    private final JdbcTemplate jdbcTemplate;

    public ConsumerDeduplicationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryClaimEvent(String consumerGroup, UUID eventMessageId) {
        int rows = jdbcTemplate.update(
                "INSERT IGNORE INTO processed_kafka_events (consumer_group, event_id, processed_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                consumerGroup,
                eventMessageId.toString()
        );
        return rows > 0;
    }
}

