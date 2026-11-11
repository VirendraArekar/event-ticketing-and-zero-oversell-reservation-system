package com.gatekeeper.messaging;

import com.gatekeeper.waitlist.WaitlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Component
public class WaitlistConsumer {

    private static final Logger log = LoggerFactory.getLogger(WaitlistConsumer.class);

    private final ConsumerDeduplicationService deduplicationService;
    private final WaitlistService waitlistService;
    private final JsonMapper jsonMapper;

    public WaitlistConsumer(
            ConsumerDeduplicationService deduplicationService,
            WaitlistService waitlistService,
            JsonMapper jsonMapper
    ) {
        this.deduplicationService = deduplicationService;
        this.waitlistService = waitlistService;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(
            topics = KafkaTopics.WAITLIST_EVENTS,
            groupId = KafkaTopics.GROUP_WAITLIST
    )
    public void onMessage(String rawPayload) {
        DomainEvents.Envelope envelope = jsonMapper.readValue(rawPayload, DomainEvents.Envelope.class);
        if (!deduplicationService.tryClaimEvent(KafkaTopics.GROUP_WAITLIST, envelope.messageId())) {
            log.debug("Duplicate waitlist event skipped: {}", envelope.messageId());
            return;
        }

        if ("SeatsReleased".equals(envelope.eventType())) {
            DomainEvents.SeatsReleasedPayload payload = jsonMapper.treeToValue(
                    envelope.data(), DomainEvents.SeatsReleasedPayload.class);
            if (payload.ticketTypeIds() != null) {
                for (UUID ticketTypeId : payload.ticketTypeIds()) {
                    waitlistService.promoteForTicketType(payload.eventId(), ticketTypeId);
                }
            }
        }
    }
}

