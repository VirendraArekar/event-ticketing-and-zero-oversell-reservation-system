package com.gatekeeper.order;

import com.gatekeeper.event.EventRepository;
import com.gatekeeper.messaging.OutboxService;
import com.gatekeeper.redis.RedisDistributedLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldExpiryAndReconciliationUnitTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderTransactionService orderTransactionService;

    @Mock
    private RedisDistributedLockService lockService;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    private HoldExpiryJob job;

    @BeforeEach
    void setUp() {
        job = new HoldExpiryJob(
                orderRepository,
                orderTransactionService,
                lockService,
                eventRepository,
                outboxService,
                redisTemplate,
                transactionTemplate,
                120L
        );
    }

    @Test
    void sweepSkippedWhenAnotherReplicaHoldsDistributedLock() {
        when(lockService.tryAcquire(anyString(), any())).thenReturn(Optional.empty());

        job.sweepExpiredOrders();

        verify(orderRepository, never()).findExpiredHoldOrderIds(any(), any());
        verify(lockService, never()).release(anyString(), anyString());
    }

    @Test
    void sweepExpiresBothHeldAndPendingPaymentOrdersAndReleasesLock() {
        UUID heldId = UUID.randomUUID();
        UUID unpaidId = UUID.randomUUID();
        when(lockService.tryAcquire(anyString(), any())).thenReturn(Optional.of("lock-token-1"));
        when(orderRepository.findExpiredHoldOrderIds(any(), any())).thenReturn(List.of(heldId));
        when(orderRepository.findExpiredPaymentOrderIds(any(), any())).thenReturn(List.of(unpaidId));
        when(orderRepository.findHoldsExpiringSoon(any(), any())).thenReturn(List.of());
        when(orderTransactionService.expireOrderIfDue(any(), any())).thenReturn(true);

        job.sweepExpiredOrders();

        verify(orderTransactionService).expireOrderIfDue(eq(heldId), any());
        verify(orderTransactionService).expireOrderIfDue(eq(unpaidId), any());
        verify(lockService).release("gatekeeper:lock:hold-expiry", "lock-token-1");

        int count = job.runSweepOnce(Instant.now());
        assertThat(count).isEqualTo(2);
    }
}
