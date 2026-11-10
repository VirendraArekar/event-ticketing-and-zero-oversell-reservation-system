package com.gatekeeper.order;

import com.gatekeeper.event.EventEntity;
import com.gatekeeper.event.EventRepository;
import com.gatekeeper.messaging.DomainEvents;
import com.gatekeeper.messaging.KafkaTopics;
import com.gatekeeper.messaging.OutboxService;
import com.gatekeeper.redis.RedisDistributedLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
    private static final String LOCK_KEY = "gatekeeper:lock:hold-expiry";
    private static final String WARNED_PREFIX = "gatekeeper:warned:hold:";

    private final OrderRepository orderRepository;
    private final OrderTransactionService orderTransactionService;
    private final RedisDistributedLockService lockService;
    private final EventRepository eventRepository;
    private final OutboxService outboxService;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final long holdExpiringWarningSeconds;

    public HoldExpiryJob(
            OrderRepository orderRepository,
            OrderTransactionService orderTransactionService,
            RedisDistributedLockService lockService,
            EventRepository eventRepository,
            OutboxService outboxService,
            StringRedisTemplate redisTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${gatekeeper.booking.hold-expiring-warning-seconds:120}") long holdExpiringWarningSeconds
    ) {
        this.orderRepository = orderRepository;
        this.orderTransactionService = orderTransactionService;
        this.lockService = lockService;
        this.eventRepository = eventRepository;
        this.outboxService = outboxService;
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = transactionTemplate;
        this.holdExpiringWarningSeconds = holdExpiringWarningSeconds;
    }

    @Scheduled(
            fixedDelayString = "${gatekeeper.booking.expiry-sweep-ms:15000}",
            initialDelayString = "${gatekeeper.booking.expiry-initial-delay-ms:15000}"
    )
    public void sweepExpiredOrders() {
        Optional<String> lockToken = lockService.tryAcquire(LOCK_KEY, Duration.ofSeconds(12));
        if (lockToken.isEmpty()) {
            return;
        }
        try {
            runSweepOnce(Instant.now());
        } finally {
            lockService.release(LOCK_KEY, lockToken.get());
        }
    }

    public int runSweepOnce(Instant now) {
        int expiredCount = 0;
        PageRequest batch = PageRequest.of(0, 100);

        List<UUID> expiredHoldIds = orderRepository.findExpiredHoldOrderIds(now, batch);
        for (UUID orderId : expiredHoldIds) {
            try {
                if (orderTransactionService.expireOrderIfDue(orderId, now)) {
                    expiredCount++;
                }
            } catch (Exception ex) {
                log.warn("Failed expiring held order {}: {}", orderId, ex.getMessage());
            }
        }

        List<UUID> expiredPaymentIds = orderRepository.findExpiredPaymentOrderIds(now, batch);
        for (UUID orderId : expiredPaymentIds) {
            try {
                if (orderTransactionService.expireOrderIfDue(orderId, now)) {
                    expiredCount++;
                }
            } catch (Exception ex) {
                log.warn("Failed expiring unpaid order {}: {}", orderId, ex.getMessage());
            }
        }

        emitExpiringHoldWarnings(now);

        if (expiredCount > 0) {
            log.info("Expired {} timed-out orders and released seats", expiredCount);
        }
        return expiredCount;
    }

    private void emitExpiringHoldWarnings(Instant now) {
        if (holdExpiringWarningSeconds <= 0) {
            return;
        }
        List<OrderEntity> expiring = orderRepository.findHoldsExpiringSoon(
                now, now.plusSeconds(holdExpiringWarningSeconds));
        for (OrderEntity order : expiring) {
            try {
                Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                        WARNED_PREFIX + order.getId(), "1", Duration.ofMinutes(20));
                if (!Boolean.TRUE.equals(claimed)) {
                    continue;
                }
                String eventTitle = eventRepository.findById(order.getEventId())
                        .map(EventEntity::getTitle)
                        .orElse("Event");
                transactionTemplate.executeWithoutResult(status -> outboxService.enqueue(
                        KafkaTopics.ORDER_EVENTS,
                        order.getEventId(),
                        "HoldExpiring",
                        new DomainEvents.HoldExpiringPayload(
                                order.getId(),
                                order.getUserId(),
                                order.getEventId(),
                                eventTitle,
                                order.getHoldExpiresAt()
                        )
                ));
            } catch (Exception ex) {
                log.debug("Hold expiring warning skipped for order={}: {}", order.getId(), ex.getMessage());
            }
        }
    }
}
