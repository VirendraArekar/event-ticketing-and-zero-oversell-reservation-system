package com.gatekeeper.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisInventoryReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(RedisInventoryReconciliationJob.class);
    private static final String LOCK_KEY = "gatekeeper:lock:inventory-reconcile";

    private final RedisDistributedLockService lockService;
    private final RedisInventoryService redisInventoryService;

    public RedisInventoryReconciliationJob(
            RedisDistributedLockService lockService,
            RedisInventoryService redisInventoryService
    ) {
        this.lockService = lockService;
        this.redisInventoryService = redisInventoryService;
    }

    @Scheduled(
            fixedDelayString = "${gatekeeper.inventory.reconcile-interval-ms:60000}",
            initialDelayString = "${gatekeeper.inventory.reconcile-initial-delay-ms:30000}"
    )
    public void reconcile() {
        Optional<String> lockToken = lockService.tryAcquire(LOCK_KEY, Duration.ofSeconds(45));
        if (lockToken.isEmpty()) {
            return;
        }
        try {
            int repaired = redisInventoryService.reconcileAllFromMySql();
            if (repaired > 0) {
                log.info("Reconciled {} Redis inventory counters from MySQL source of truth", repaired);
            }
        } finally {
            lockService.release(LOCK_KEY, lockToken.get());
        }
    }
}

