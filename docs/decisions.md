# Architecture Decision Records (ADRs)

## ADR-001: Conditional MySQL `UPDATE` vs Pessimistic `SELECT ... FOR UPDATE`

**Context**
Reserving limited ticket inventory must prevent overselling under arbitrary concurrency across multiple backend replicas while keeping connection hold times short.

**Decision**
Use a single-statement conditional `UPDATE` on `ticket_inventory`:
```sql
UPDATE ticket_inventory
SET held = held + :qty, updated_at = :now
WHERE ticket_type_id = :ticketTypeId
  AND (held + sold + :qty) <= total
```
coupled with deterministic sorting of `ticketTypeId` values, a schema-level `CHECK (held + sold <= total)` constraint, and an atomic Redis Lua fast-reject front door ([InventoryService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/order/InventoryService.java), [RedisInventoryService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/redis/RedisInventoryService.java)).

**Consequences**
- **Pros**: Eliminates a round-trip between `SELECT ... FOR UPDATE` and `UPDATE`, keeps InnoDB exclusive locks minimal, avoids lost-update races without JVM-side locks, and composes cleanly with Redis front-door rejection so sold-out floods don't saturate DB connections.
- **Cons**: Requires a compensating follow-up read only on `rowsUpdated == 0` to distinguish sold-out from partial remaining quantity, and relies on scheduled reconciliation to repair Redis drift after crashes.

---

## ADR-002: Two-Phase Seat Holds vs Immediate Purchase

**Context**
Users take time to review selections, inspect pricing, or coordinate group purchases; offline payments require time before funds settle. Holding no seats until confirmation creates poor UX at checkout, while holding seats indefinitely starves other buyers.

**Decision**
Implement a mandatory **10-minute (`600s`) hold phase** (`POST /api/orders/hold -> HELD`) before confirmation (`POST /api/orders/{orderId}/confirm`), backed by a Redis-locked scheduled sweeper ([HoldExpiryJob.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/order/HoldExpiryJob.java)) that idempotently expires unconfirmed holds and overdue `PENDING_PAYMENT` offline orders, freeing seats back to MySQL, Redis, and the FIFO waitlist.

**Consequences**
- **Pros**: Eliminates "cart vanished at confirm" races for honest buyers while bounding starvation to a strict TTL; supports offline settlement cleanly via `OfflinePaymentProvider` + `payment_ledger`.
- **Cons**: Adds an additional state (`HELD` / `EXPIRED`) and requires idempotent distributed-lock cleanup across backend replicas.

---

## ADR-003: Transactional Outbox + Kafka vs Direct Broker Publish

**Context**
Domain state changes (order holds, confirmations, expirations, refunds, check-ins, event updates) must update dashboard projections, promote waitlisted users, and emit in-app notifications reliably without two-phase commits (XA) across MySQL and Kafka.

**Decision**
Persist every domain event into `outbox_events` inside the **same ACID MySQL transaction** as the business state mutation via [OutboxService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/messaging/OutboxService.java). An asynchronous poller ([OutboxRelayJob.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/messaging/OutboxRelayJob.java)) publishes batches keyed by `eventId` to partitioned Kafka topics, and independent idempotent consumers deduplicate on `(consumer_group, event_id)` in `processed_kafka_events` with retry backoff and `.DLT` dead-letter routing.

**Consequences**
- **Pros**: Guarantees at-least-once delivery with strict per-event ordering (`key = eventId`), avoids lost events on broker outages or post-commit JVM crashes, and isolates DB write latency from Kafka broker round-trips.
- **Cons**: Introduces sub-second (`relay-interval-ms: 500`) eventual consistency between ticket checkout/check-in and dashboard aggregates/notifications.

---

## ADR-004: Cryptographic Signed QR Tokens vs Random Codes Alone

**Context**
Check-in staff must validate tickets quickly at the door without trusting predictable client-supplied UUIDs or leaking attendee personal data inside QR barcodes.

**Decision**
Issue every ticket both:
1. A human-readable unique `ticketCode` (`GK-...`) for manual keyboard fallback (`uk_tickets_code`), and
2. A compact signed QR token (`gk1.<ticketId>.<eventId>.<expiryEpoch>.<base64url(HMAC-SHA256)>`) generated in [QrTokenService.java](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/java/com/gatekeeper/ticket/QrTokenService.java) using a secret from `QR_HMAC_SECRET`, validated using constant-time `MessageDigest.isEqual(...)` before touching the database. No name, email, or PII is embedded in the QR payload.

**Consequences**
- **Pros**: Forged or tampered QR tokens fail cryptographic verification immediately; event mismatches and expired tokens can be identified locally before DB locking; scanning cameras never expose PII over untrusted screens/logs.
- **Cons**: Requires synchronized HMAC secret rotation if `QR_HMAC_SECRET` ever rotates, and manual `ticketCode` fallback remains server-verified via MySQL uniqueness lookup.

