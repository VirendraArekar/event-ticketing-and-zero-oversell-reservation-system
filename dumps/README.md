# GateKeeper 200-Event Sample Dataset & MySQL Dump

By default, GateKeeper boots with an empty database (`Flyway` runs schema migrations only in [V1__init_schema.sql](file:///Users/apple/Desktop/Antigravity/Project/backend/src/main/resources/db/migration/V1__init_schema.sql)). This folder provides both an instant MySQL SQL dump and a live API generator so you can populate **200+ realistic events** and complete ticketing workflows on demand.

---

## Option A — Live API Seeder (Recommended for Fresh `.env` Secrets)

Populates 200 published events plus 12 lifecycle events (`DRAFT`, `COMPLETED`, `CANCELLED`), ticket tiers, seat holds, confirmed/paid/refunded orders, HMAC-signed QR tickets using your active `.env` `QR_HMAC_SECRET`, door check-ins, waitlists, notifications, and Kafka dashboard aggregates:

```bash
./setup.sh
node scripts/seed-200.mjs
```

> Uses built-in Node.js `fetch` (Node 20+ / 24, zero extra packages) and automatically respects Redis token-bucket rate limits.

---

## Option B — Direct MySQL SQL Dump Import

Imports [gatekeeper-200.sql](file:///Users/apple/Desktop/Antigravity/Project/dumps/gatekeeper-200.sql) directly into the running MySQL 8 container and flushes Redis caches so counters lazily warm and reconcile from MySQL:

```bash
./setup.sh
./scripts/load-dump.sh
```

> Note on QR cryptographic verification: Every QR ticket row in `gatekeeper-200.sql` stores an HMAC-SHA256 signature computed with the `.env` `QR_HMAC_SECRET` active when the dump was exported. Manual `ticketCode` (`GK-...`) fallback check-in, QR/PDF image rendering, dashboards, orders, and event search all work across any `.env`; if you want scanned `qrToken` cryptographic signatures to validate against a brand-new `.env`, run **Option A** (`node scripts/seed-200.mjs`) instead.

---

## Demo Accounts Included in Dataset

Password for all seeded accounts (`DEMO_PASSWORD` default): `GateKeeperDemo!1`

| Role / Purpose | Sample Email |
|---|---|
| Organizer (owns 35 published + all 12 lifecycle showcase events, rich dashboard metrics) | `organizer1@gatekeeper.demo` .. `organizer6@gatekeeper.demo` |
| Door Staff (assigned to active-window events for `/scanner`) | `staff1@gatekeeper.demo` .. `staff4@gatekeeper.demo` |
| Attendee (orders, QR/PDF tickets, waitlists, notifications) | `attendee1@gatekeeper.demo` .. `attendee30@gatekeeper.demo` |

To regenerate `dumps/gatekeeper-200.sql` after modifying `scripts/seed-200.mjs`:

```bash
./scripts/dump-200.sh
```

