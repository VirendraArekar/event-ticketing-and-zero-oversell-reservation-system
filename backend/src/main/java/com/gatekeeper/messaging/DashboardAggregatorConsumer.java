package com.gatekeeper.messaging;

import com.gatekeeper.dashboard.DashboardAggregatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DashboardAggregatorConsumer {

    private static final Logger log = LoggerFactory.getLogger(DashboardAggregatorConsumer.class);

    private final ConsumerDeduplicationService deduplicationService;
    private final DashboardAggregatorService aggregatorService;
    private final JsonMapper jsonMapper;

    public DashboardAggregatorConsumer(
            ConsumerDeduplicationService deduplicationService,
            DashboardAggregatorService aggregatorService,
            JsonMapper jsonMapper
    ) {
        this.deduplicationService = deduplicationService;
        this.aggregatorService = aggregatorService;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(
            topics = {
                    KafkaTopics.EVENT_LIFECYCLE_EVENTS,
                    KafkaTopics.ORDER_EVENTS,
                    KafkaTopics.CHECKIN_EVENTS
            },
            groupId = KafkaTopics.GROUP_DASHBOARD
    )
    @Transactional
    public void onMessage(String rawPayload) {
        DomainEvents.Envelope envelope = jsonMapper.readValue(rawPayload, DomainEvents.Envelope.class);
        if (!deduplicationService.tryClaimEvent(KafkaTopics.GROUP_DASHBOARD, envelope.messageId())) {
            log.debug("Duplicate dashboard event skipped: {}", envelope.messageId());
            return;
        }

        switch (envelope.eventType()) {
            case "EventPublished" -> aggregatorService.onEventPublished(
                    jsonMapper.treeToValue(envelope.data(), DomainEvents.EventPublishedPayload.class));
            case "TicketTypeUpserted" -> aggregatorService.onTicketTypeUpsert(
                    jsonMapper.treeToValue(envelope.data(), DomainEvents.TicketTypeUpsertPayload.class));
            case "OrderConfirmed" -> aggregatorService.onOrderConfirmed(
                    jsonMapper.treeToValue(envelope.data(), DomainEvents.OrderConfirmedPayload.class));
            case "OrderRefunded" -> aggregatorService.onOrderRefunded(
                    jsonMapper.treeToValue(envelope.data(), DomainEvents.OrderRefundedPayload.class));
            case "TicketCheckedIn" -> aggregatorService.onTicketCheckedIn(
                    jsonMapper.treeToValue(envelope.data(), DomainEvents.TicketCheckedInPayload.class));
            default -> log.debug("DashboardAggregatorConsumer ignored eventType={}", envelope.eventType());
        }
    }
}

