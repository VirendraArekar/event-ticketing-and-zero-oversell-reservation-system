# GateKeeper — Event Management & Ticketing Platform

GateKeeper is an end-to-end, concurrency-safe event management, seat-reservation, cryptographic QR ticketing, and door check-in platform built for high-demand ticket drops. It starts completely empty with zero seed/dummy data; all events, orders, tickets, notifications, waitlist promotions, and dashboard aggregates are created through real user actions.

---

## One-Command Startup

### Prerequisites
- Docker Desktop / Docker Engine with Compose v2 (`docker compose`)
- `openssl` (macOS/Linux) or PowerShell (Windows) to generate random secrets into `.env`

### macOS / Linux
```bash
./setup.sh
```

### Windows (PowerShell)
```powershell
.\setup.ps1
```

Both setup scripts generate `.env` with cryptographically random secrets (`MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `JWT_SECRET`, `QR_HMAC_SECRET`), run `docker compose up --build -d`, and wait until `http://localhost/actuator/health` reports healthy.

Once running:
- **Web App & API Gateway (Nginx)**: `http://localhost`
- **Health Check**: `http://localhost/actuator/health`
- **Optional Prometheus + Grafana Monitoring Stack**:
  ```bash
  docker compose --profile monitoring up -d
  ```
  - Prometheus: `http://localhost:9090`
  - Grafana: `http://localhost:3001`

---

## Core Feature Set

### Identity & Role-Based Access Control
- Email/password registration and login backed by **BCrypt** password hashing.
- Short-lived stateless **JWT access tokens** (`900s`) paired with **rotating opaque refresh tokens** persisted as SHA-256 hashes in MySQL (`refresh_tokens`) with family revocation on logout.
- Multi-role model across events: a user can register as `ATTENDEE` and/or `ORGANIZER`, and organizers assign per-event `STAFF` door scanners. Ownership and assignment checks are enforced in backend services (`EventAuthorizationService`).

### Event & Inventory Lifecycle
- Organizers create events (`DRAFT`), define ticket tiers (`TicketTypeEntity` with capacity, price, per-order limit, and sales window), and enforce the invariant `SUM(ticket_types.capacity) <= events.venue_capacity`.
- Lifecycle transitions: `DRAFT -> PUBLISHED -> COMPLETED` or `CANCELLED` (optimistic locking via `@Version`).
- Public catalog (`GET /api/events`) supports keyword search, category filtering, start-time window filters, pagination, Redis response caching with explicit eviction on mutations, and `Cache-Control` headers.

### Time-Limited Seat Holds & Offline Payment
- Two-phase booking: `POST /api/orders/hold` places a **10-minute (`600s`) reservation** (`HELD`), locking inventory in Redis (fast front door) and MySQL (`ticket_inventory`).
- `PaymentProvider` interface with `OfflinePaymentProvider` implementation:
  - **Free orders (`price_cents == 0`)**: confirm immediately (`CONFIRMED`) and issue QR tickets.
  - **Paid orders (`price_cents > 0`)**: transition to `PENDING_PAYMENT` with an explicit payment deadline until the organizer marks payment received (`POST /api/organizer/events/{eventId}/orders/{orderId}/mark-paid`).
- Every state change appends an immutable audit row to `payment_ledger` (`INITIATED`, `HELD`, `AUTHORIZED_PENDING`, `CAPTURED`, `REFUNDED`, `RELEASED`).
- Scheduled distributed-lock cleanup (`HoldExpiryJob`) releases expired holds and overdue unpaid orders idempotently every `15s`.
- Attendee cancellation before the organizer-configured cutoff releases sold seats back to inventory and writes a `REFUNDED` ledger entry.

### Cryptographic QR Tickets, PDFs & Atomic Check-In
- Confirmed tickets receive a unique human-readable `ticketCode` (`GK-...`) and an **HMAC-SHA256** signed QR payload (`gk1.<ticketId>.<eventId>.<expiryEpoch>.<base64urlSig>`) verified with constant-time comparison (`MessageDigest.isEqual`). No PII is stored inside the QR code.
- QR PNGs (`GET /api/tickets/{id}/qr`) are generated server-side via **ZXing**; printable PDF tickets (`GET /api/tickets/{id}/pdf`) are generated server-side via **OpenPDF**.
- Door check-in (`POST /api/events/{eventId}/checkin`) accepts either a QR token (camera scan via `@zxing/browser` on the mobile-first `/scanner` page) or manual `ticketCode` fallback, validates event window, ticket status, and staff assignment, and executes an atomic conditional `UPDATE` (`CHECKED_IN`). Duplicate concurrent scans reject cleanly (`409 Conflict` with `"Already checked in at <timestamp> by <staff>"`).

### Event-Driven Outbox, Waitlist & Pre-Aggregated Analytics
- Domain mutations persist events into `outbox_events` inside the **same MySQL transaction**, published to partitioned **Apache Kafka** (KRaft) topics (`order-events`, `ticket-events`, `checkin-events`, `waitlist-events`, `event-lifecycle-events`) by `OutboxRelayJob` partitioned by `eventId`.
- Idempotent consumer groups deduplicate via `processed_kafka_events` and route poison payloads after bounded exponential backoff to `.DLT` dead-letter topics:
  - **Notifications**: real-time in-app notifications (`ORDER_CONFIRMED`, `HOLD_EXPIRING`, `WAITLIST_AVAILABLE`, `EVENT_UPDATED`, `EVENT_CANCELLED`, `CHECKIN_RECORDED`).
  - **Dashboard Aggregator**: maintains `event_dashboard_stats`, `ticket_type_dashboard_stats`, 15-minute `event_checkin_buckets`, and `event_sales_buckets` for fast constant-time dashboard reads and CSV attendee export (`GET /api/organizer/events/{eventId}/attendees/export`).
  - **Waitlist Consumer**: FIFO promotion of `WAITING` entries when seats return to inventory via cancellation or hold expiry, automatically creating a short limited-time hold and notifying the attendee.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 25, Spring Boot 4.1.2, Maven, Spring Security (JWT + BCrypt), Spring Data JPA (`open-in-view: false`, `ddl-auto: validate`) |
| Database & Migrations | MySQL 8.4 (InnoDB), Flyway (`V1__init_schema.sql`) |
| Cache, Rate Limits & Locks | Redis 7.4 (Lua inventory front door, Lua token bucket rate limiter, ZSET waiting room, distributed scheduler lock, read-through event cache) |
| Messaging | Apache Kafka 3.9.0 in KRaft mode (no Zookeeper) |
| Gateway | Nginx 1.27 Alpine (round-robin across `backend-1` and `backend-2`, health checks, `X-Request-ID`, `frontend` routing) |
| Frontend | Next.js 16.4 (App Router), React 19, TypeScript 5.9, Tailwind CSS v4, `@zxing/browser` |
| Tickets & Docs | ZXing `3.5.4` (QR PNG), OpenPDF `3.0.5` (PDF generation) |
| Observability | Spring Boot Actuator `/actuator/health`, Prometheus `/actuator/prometheus`, ECS structured JSON logs |
| Testing | JUnit 5, Mockito, Testcontainers (MySQL 8.4, Redis 7.4, Kafka 3.9), Vitest + Testing Library, Playwright Chromium, k6 |

---

## Running Tests & Load Benchmarks

### Backend Unit & Integration Tests (Testcontainers)
```bash
mvn -f backend/pom.xml test -B
```
Runs 19 tests across unit tests and real Testcontainers integration suites (`Phase1SchemaIT` through `Phase6KafkaOutboxDashboardWaitlistIT`), including a 500-concurrent-request booking race for 50 seats.

### Frontend Checks, Component Tests & End-to-End Tests
```bash
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend test

# With Docker Compose stack running at http://localhost:
E2E_BASE_URL=http://localhost npm --prefix frontend run test:e2e
```

### k6 Load Test (Live Stack)
```bash
docker run --rm --network project_default \
  -v "$(pwd)/k6:/scripts" \
  grafana/k6:latest run /scripts/load-test.js
```

---

## Loading the 200-Event Sample Dataset & SQL Dump

By default, a clean startup runs schema-only Flyway migrations and starts empty. To populate **200 published events** (+ 12 lifecycle events, 412 ticket types, 102 orders, 135 QR tickets, 70 door check-ins, waitlists, notifications, and Kafka dashboard metrics):

```bash
# Option A: Fast SQL dump import into MySQL + Redis flush
./scripts/load-dump.sh

# Option B: Re-seed dynamically via the live HTTP API (signs QR tokens with active .env secret)
node scripts/seed-200.mjs
```

See [dumps/README.md](dumps/README.md) for demo organizer/staff/attendee logins (`GateKeeperDemo!1`) and instructions to re-export [dumps/gatekeeper-200.sql](dumps/gatekeeper-200.sql) via [scripts/dump-200.sh](scripts/dump-200.sh).

---

## Further Documentation
- [System Architecture, Sequences, ER Model, Indexes & Scaling to 100x](docs/architecture.md)
- [Concurrency, Inventory Protection & Benchmark Proof](docs/concurrency.md)
- [Architecture Decision Records (ADRs)](docs/decisions.md)
- [REST API Reference & Error Contract](docs/api.md)
- [200-Event Sample Dataset & SQL Dump Guide](dumps/README.md)

