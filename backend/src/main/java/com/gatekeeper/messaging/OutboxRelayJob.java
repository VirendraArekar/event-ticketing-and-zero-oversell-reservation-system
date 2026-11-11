package com.gatekeeper.messaging;

import com.gatekeeper.redis.RedisDistributedLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);
    private static final String LOCK_KEY = "gatekeeper:lock:outbox-relay";
    private static final int MAX_RETRIES = 10;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisDistributedLockService lockService;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelayJob(
            OutboxEventRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            RedisDistributedLockService lockService,
            TransactionTemplate transactionTemplate
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.lockService = lockService;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(
            fixedDelayString = "${gatekeeper.outbox.relay-interval-ms:500}",
            initialDelayString = "${gatekeeper.outbox.initial-delay-ms:1000}"
    )
    public void relayPendingEvents() {
        Optional<String> lockToken = lockService.tryAcquire(LOCK_KEY, Duration.ofSeconds(10));
        if (lockToken.isEmpty()) {
            return;
        }
        try {
            publishPendingBatch();
        } finally {
            lockService.release(LOCK_KEY, lockToken.get());
        }
    }

    public int publishPendingBatch() {
        List<OutboxEventEntity> batch = outboxRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING, PageRequest.of(0, 100));
        int published = 0;

        for (OutboxEventEntity event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);
                transactionTemplate.executeWithoutResult(status -> {
                    outboxRepository.findById(event.getId()).ifPresent(fresh -> {
                        if (fresh.getStatus() == OutboxStatus.PENDING) {
                            fresh.markPublished(Instant.now());
                            outboxRepository.save(fresh);
                        }
                    });
                });
                published++;
            } catch (Exception ex) {
                log.warn("Failed publishing outbox event {} to topic {}: {}",
                        event.getId(), event.getTopic(), ex.getMessage());
                transactionTemplate.executeWithoutResult(status -> {
                    outboxRepository.findById(event.getId()).ifPresent(fresh -> {
                        fresh.recordFailure(ex.getMessage(), MAX_RETRIES);
                        outboxRepository.save(fresh);
                    });
                });
            }
        }
        return published;
    }
}

