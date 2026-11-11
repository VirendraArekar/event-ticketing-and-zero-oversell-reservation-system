package com.gatekeeper.messaging;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, JsonMapper jsonMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
    }

    public UUID enqueue(String topic, UUID eventId, String eventType, Object data) {
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();
        JsonNode dataNode = jsonMapper.valueToTree(data);
        DomainEvents.Envelope envelope = new DomainEvents.Envelope(
                messageId,
                eventType,
                eventId,
                now,
                dataNode
        );
        String payload = jsonMapper.writeValueAsString(envelope);
        OutboxEventEntity entity = new OutboxEventEntity(
                messageId,
                topic,
                eventId.toString(),
                eventType,
                payload,
                now
        );
        outboxEventRepository.save(entity);
        return messageId;
    }
}

