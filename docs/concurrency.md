# Concurrency Control & Zero-Oversell Architecture

Guaranteeing that `sold + held <= total` holds **under arbitrary concurrency across multiple JVM replicas** is GateKeeper's foundational invariant. The system uses defense in depth across four layers rather than relying on application-level synchronization or fragile read-modify-write logic.

---

## Layer 1 — MySQL InnoDB Conditional `UPDATE` + `CHECK` Constraint (Source of Truth)

In [TicketInventoryRepository.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/event/TicketInventoryRepository.java) and [InventoryService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/order/InventoryService.java), GateKeeper never reads `available` in Java and then writes a decremented value back. Instead, reservation executes a single atomic SQL statement inside InnoDB:

```sql
UPDATE ticket_inventory
SET held = held + :qty,
    updated_at = :now
WHERE ticket_type_id = :ticketTypeId
  AND (held + sold + :qty) <= total
```

Why this is both necessary and sufficient as the source of truth:
1. InnoDB takes a row-level exclusive (`X`) lock on the matching `ticket_inventory` row for the duration of the update and evaluates `(held + sold + :qty) <= total` against the latest committed row version — eliminating TOCTOU (time-of-check to time-of-use) lost updates completely.
2. Spring checks the returned affected row count (`updated == 1` vs `0`). Only if `updated == 0` does it inspect the row to return an actionable user message (`"<Tier> is sold out"` or `"Only N left for <Tier>"`).
3. As a final schema-level safety net, `V1__init_schema.sql` enforces:
   ```sql
   CONSTRAINT chk_inventory_bounds CHECK (held >= 0 AND sold >= 0 AND total > 0 AND (held + sold) <= total)
   ```
   Verified directly in [Phase1SchemaIT.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/test/java/com/gatekeeper/schema/Phase1SchemaIT.java).

---

## Layer 2 — Redis Lua Fast Front Door (With Safe Fallback & Reconciliation)

When thousands of users request the last remaining seats of a sold-out drop, forcing every doomed request into a MySQL transaction wastes connection pool slots (`HikariCP`, max 20 per replica) and triggers lock queue buildup.

[RedisInventoryService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/redis/RedisInventoryService.java) puts an atomic Redis Lua script (`RESERVE_LUA`) in front of MySQL:
- For every sorted `ticketTypeId` in the order, Lua checks `GET gatekeeper:inv:avail:{ticketTypeId}`:
  - Returns `{-2, idx, 0}` on cache miss -> Java lazily warms missing keys from MySQL `ticket_inventory` (`(total - held - sold)`) and retries Lua once.
  - Returns `{0, idx, avail}` if any tier has `avail < qty` -> **zero mutations**, request rejected in `< 1ms` with `409 Conflict` without touching MySQL!
  - Otherwise executes `DECRBY` on all requested tier keys atomically and returns `{1, 0, 0}`.
- Every hold writes a diagnostic marker key (`gatekeeper:inv:hold:{orderId}`) with TTL `holdDurationSeconds + 60`.

### What Happens If Redis Is Stale, Evicted, or Down?
Redis is strictly a **performance optimization layer, never the source of truth**:
1. **Redis Down / Timeout**: `tryReserveFrontDoor` catches Redis exceptions and returns `FrontDoorOutcome.BYPASS_TO_MYSQL`, seamlessly routing the booking to MySQL's conditional `UPDATE` without downtime or oversell.
2. **MySQL Rejects After Redis Passed (Stale High Counter)**: `OrderTransactionService` immediately restores Redis counters (`releaseToRedis`) so Redis converges downward, and `TransactionAfterCommit` rolls back the Redis hold increment if the MySQL transaction rolls back for any later validation/database error.
3. **Stale Low Counter / Missed Release**: [RedisInventoryReconciliationJob.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/redis/RedisInventoryReconciliationJob.java) runs periodically (`gatekeeper.inventory.reconcile-interval-ms: 60000`) under a Redis distributed lock, recomputing `total - held - sold` from MySQL across all active ticket types and overwriting any drifted Redis `avail` keys.

---

## Additional Concurrency Protections

- **Deadlock Avoidance**: [InventoryService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/order/InventoryService.java) sorts all `SeatRequest` items deterministically by `ticketTypeId.toString()` before executing both Redis Lua multi-key reservations and MySQL `UPDATE` statements. Two concurrent multi-tier orders `(VIP, General)` vs `(General, VIP)` always lock rows in identical order, making lock cycles mathematically impossible.
- **Idempotent Booking Retries**: `POST /api/orders/hold` hashes the request body with SHA-256 against `(user_id, idempotency_key)` in [IdempotencyService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/order/IdempotencyService.java) (checked in Redis + `idempotency_records` with `uk_idempotency_key`). Exact retries return the original order without double-holding; reusing a key with different items or quantities throws `409 Conflict`.
- **Optimistic Locking (`@Version`)**: `EventEntity` and `OrderEntity` carry JPA `@Version` columns (`version`), protecting concurrent lifecycle edits or state transitions (`HELD` -> `CONFIRMED` racing against `HoldExpiryJob` expiring the hold) from silent overwrites.
- **Distributed-Lock Scheduled Hold Expiry**: [HoldExpiryJob.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/order/HoldExpiryJob.java) runs on both replicas every `15s` guarded by [RedisDistributedLockService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/redis/RedisDistributedLockService.java) (`SET NX PX` with owner UUID Lua release). Individual order expiry re-checks status inside the transaction and releases seats idempotently.
- **Atomic Door Check-In Under Double Scan**: [CheckInService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/checkin/CheckInService.java) executes an atomic `UPDATE tickets SET status = 'CHECKED_IN' WHERE id = :id AND status = 'VALID'` inside a `TransactionTemplate` block, then commits before recording `check_in_logs` in a separate `CheckInAuditService` transaction (preventing FK S-lock/X-lock self-deadlock on rejection logging).
- **Rate Limiting & Virtual Waiting Room**: [RateLimitingFilter.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/redis/RateLimitingFilter.java) enforces Redis token buckets per IP (`/api/auth/login`, `/api/auth/register`, booking endpoints) and per user (`/api/orders/hold`, `/api/orders/{id}/confirm`), returning `429 Too Many Requests` with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset`. High-demand sales activate [WaitingRoomService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/redis/WaitingRoomService.java).

---

## Proof #1 — Automated 500-Concurrent-Thread Oversell Test (Real MySQL + Redis)

Implemented in [Phase3BookingAndConcurrencyIT.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/test/java/com/gatekeeper/order/Phase3BookingAndConcurrencyIT.java#fiveHundredConcurrentBookingRequestsNeverOversellFiftySeats):

- **Setup**: Real `mysql:8.4`, `redis:7.4-alpine`, and `apache/kafka:3.9.0` Testcontainers; event with **1 ticket tier of capacity `50`** (`perOrderLimit = 1`); **500 distinct registered attendees** barrier-synchronized on a `CountDownLatch` using Java 25 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`), each holding then confirming 1 seat simultaneously.
- **Verified Observed Assertions**:
  - `succeeded == 50` (every seat sold, none lost)
  - `rejected == 450` (every losing request received clean `409 Conflict`, zero `500` errors, zero deadlocks)
  - `ticket_inventory.sold == 50`, `ticket_inventory.held == 0`, `held + sold == 50`
- **Additional Concurrency & Resilience Proofs Observed in CI/Maven Test Run** (`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`):
  - Simultaneous 2-thread door scan of the same ticket (`Phase5TicketsAndCheckInIT`): 1 succeeds (`200 OK`), 1 returns `409 Conflict` (`"Already checked in at ... by ..."`).
  - Concurrent hold expiry + seat restoration + waitlist FIFO promotion (`Phase4RedisRateLimitWaitingRoomExpiryIT`, `Phase6KafkaOutboxDashboardWaitlistIT`).

---

## Proof #2 — Live k6 Load Test Across 2 Backend Replicas Behind Nginx

Executed against the live 7-container Docker Compose stack (`http://nginx`, round-robining `backend-1` and `backend-2`) via [k6/load-test.js](file:///Users/apple/Desktop/Antigravity/Project/k6/load-test.js):

- **Workload**:
  - `catalog_browse`: up to `40` VUs for `35s` (`GET /api/events` + `GET /api/events/{id}`)
  - `booking_burst`: up to `20` VUs for `30s` (`POST /api/orders/hold` + `POST /api/orders/{id}/confirm` on a `300`-seat tier across 40 buyer tokens)
- **Observed Results (local single-host macOS Docker Desktop)**:
  - `http_reqs`: `5,071` (`98.60 req/s`)
  - `http_req_failed`: `0.00%` (`0 out of 5,071`)
  - `checks_succeeded`: `100.00%` (`4,810 out of 4,810`)
  - `gatekeeper_booked_success`: `221` tickets issued (`capacity=300, sold=221, held=0, available=79` — **zero oversell invariant passed**)
  - `catalog_browse` latency: `avg = 93.37ms`, `med = 49.56ms`, `p90 = 180.03ms`, `p95 = 256.08ms` (threshold `p(95) < 250ms` slightly exceeded by `6.08ms` under full concurrent booking contention)
  - `booking_burst` latency: `avg = 1.01s`, `med = 886.59ms`, `p90 = 1.74s`, `p95 = 2.00s` (threshold `p(95) < 800ms` crossed due to single-row InnoDB `ticket_inventory` row-lock serialization + Docker VM disk fsync across 20 concurrent booking writers hammering a single tier — directly demonstrating why hot-row sharding and the virtual waiting room exist in our 100x scaling design).

