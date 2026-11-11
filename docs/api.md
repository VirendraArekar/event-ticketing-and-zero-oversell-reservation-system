# GateKeeper REST API Reference

Base URL behind Nginx gateway: `/api` (e.g., `http://localhost/api/...`).

## Conventions & Error Contract

- **Authentication**: `Authorization: Bearer <accessToken>` on all protected routes.
- **Request Tracing**: Every response echoes `X-Request-ID` and `X-GateKeeper-Replica` (`backend-1` / `backend-2`).
- **Rate Limiting**: `POST` auth and booking endpoints return `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, and `429 Too Many Requests` with `Retry-After` when exceeded.
- **Consistent Error Body**:
  ```json
  {
    "message": "Validation failed",
    "fieldErrors": {
      "email": "must be a well-formed email address"
    },
    "traceId": "c44296b4dc3582d73abb0cf70bd297bc"
  }
  ```

---

## 1. Authentication (`/api/auth`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/register` | Public | Register a new user (`ATTENDEE`, `ORGANIZER`) and return access + refresh tokens. |
| `POST` | `/api/auth/login` | Public | Authenticate with email/password. |
| `POST` | `/api/auth/refresh` | Public | Exchange rotating opaque refresh token for fresh access + refresh tokens. |
| `POST` | `/api/auth/logout` | Optional Bearer | Revoke refresh token family. |
| `GET` | `/api/auth/me` | Authenticated | Return current user profile and roles. |

Example `POST /api/auth/register`:
```json
{
  "email": "organizer@gatekeeper.local",
  "password": "StrongPassword!1",
  "fullName": "Alex Rivera",
  "roles": ["ORGANIZER", "ATTENDEE"]
}
```

---

## 2. Public Event Discovery (`/api/events`)

Includes `Cache-Control: max-age=15, public, stale-while-revalidate=30`.

| Method | Path | Query Params | Description |
|---|---|---|---|
| `GET` | `/api/events` | `search`, `category`, `from` (ISO-8601), `to` (ISO-8601), `page=0`, `size=12` | Paginated search over `PUBLISHED` events. |
| `GET` | `/api/events/{eventId}` | — | Public event detail with live ticket tier availability. |

---

## 3. Organizer Studio (`/api/organizer/events`)

Requires `ORGANIZER` role AND ownership (`event.organizerId == currentUser.id`).

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/organizer/events` | Create a `DRAFT` event (`venueCapacity > 0`, `endTime > startTime`). |
| `GET` | `/api/organizer/events` | Paginated list of organizer's own events (`page`, `size`). |
| `GET` | `/api/organizer/events/{eventId}` | Organizer event detail including inventory stats. |
| `PUT` | `/api/organizer/events/{eventId}` | Update event details (emits `EVENT_UPDATED` outbox event if published). |
| `POST` | `/api/organizer/events/{eventId}/publish` | Publish draft event (requires at least 1 ticket tier). |
| `POST` | `/api/organizer/events/{eventId}/cancel` | Cancel event and notify attendees (`EVENT_CANCELLED`). |
| `POST` | `/api/organizer/events/{eventId}/complete` | Mark event completed. |
| `POST` | `/api/organizer/events/{eventId}/ticket-types` | Add ticket tier (enforces sum of capacities <= `venueCapacity`). |
| `PUT` | `/api/organizer/events/{eventId}/ticket-types/{ticketTypeId}` | Update ticket tier (enforces sum <= `venueCapacity` and `total >= held + sold`). |
| `POST` | `/api/organizer/events/{eventId}/staff` | Assign a user by email as door `STAFF` for this event. |
| `GET` | `/api/organizer/events/{eventId}/staff` | List assigned staff for this event. |
| `DELETE` | `/api/organizer/events/{eventId}/staff/{staffUserId}` | Remove staff assignment. |
| `GET` | `/api/organizer/events/{eventId}/orders` | Paginated orders for the event. |
| `POST` | `/api/organizer/events/{eventId}/orders/{orderId}/mark-paid` | Mark `PENDING_PAYMENT` order settled offline -> `CONFIRMED` + mint tickets. |
| `GET` | `/api/organizer/events/{eventId}/dashboard` | Pre-aggregated metrics, tier breakdown, check-in/sales 15-min curves. |
| `GET` | `/api/organizer/events/{eventId}/attendees` | Paginated attendee/ticket list (`page`, `size`). |
| `GET` | `/api/organizer/events/{eventId}/attendees/export` | Stream CSV download (`text/csv`) of attendees & check-in status. |
| `GET` | `/api/organizer/events/{eventId}/waitlist` | Paginated waitlist queue for the event. |

Example `POST /api/organizer/events`:
```json
{
  "title": "Systems Summit 2026",
  "description": "Distributed engineering conference",
  "category": "Conference",
  "venueName": "Harbor Hall",
  "venueAddress": "100 Pier Way",
  "venueCapacity": 500,
  "startTime": "2026-12-01T18:00:00Z",
  "endTime": "2026-12-01T22:00:00Z",
  "timezone": "UTC",
  "cancellationCutoffAt": "2026-11-30T18:00:00Z",
  "waitingRoomEnabled": false,
  "waitingRoomThreshold": 25
}
```

---

## 4. Virtual Waiting Room (`/api/events/{eventId}/waiting-room`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/events/{eventId}/waiting-room/enter` | Authenticated | Enter Redis ZSET queue or receive immediate admission token. |
| `GET` | `/api/events/{eventId}/waiting-room/status?token=...` | Authenticated | Poll position, estimated wait, and promotion to `ADMITTED`. |

When waiting room admission is required on `POST /api/orders/hold` (explicit `waitingRoomEnabled` or auto-spike threshold exceeded) without a valid `X-Waiting-Room-Token`, the API responds with **`428 Precondition Required`** containing `fieldErrors.queueToken` and `fieldErrors.position`.

---

## 5. Booking, Orders & Offline Payments (`/api/orders`)

| Method | Path | Headers / Params | Description |
|---|---|---|---|
| `POST` | `/api/orders/hold` | `Idempotency-Key` (required), optional `X-Waiting-Room-Token` | Reserve seats for `600s` (`HELD`). Returns `409` when sold out or idempotency payload mismatches. |
| `POST` | `/api/orders/{orderId}/confirm` | — | Runs `OfflinePaymentProvider`: free orders -> `CONFIRMED` + tickets; paid orders -> `PENDING_PAYMENT`. |
| `POST` | `/api/orders/{orderId}/cancel` | — | Release `HELD` / `PENDING_PAYMENT` order, or cancel/refund `CONFIRMED` order prior to `cancellationCutoffAt`. |
| `GET` | `/api/orders` | `page=0`, `size=10` | Paginated attendee orders ("My Orders") with embedded payment ledger entries. |
| `GET` | `/api/orders/{orderId}` | — | Fetch single order owned by current user. |

Example `POST /api/orders/hold` (`Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000`):
```json
{
  "eventId": "c1f8a220-7d1e-4e92-b78c-1f2e3a4b5c6d",
  "items": [
    { "ticketTypeId": "a9d0b111-2c3d-4e5f-8901-abcdef123456", "quantity": 2 }
  ]
}
```

---

## 6. Tickets, QR PNG & PDF Downloads (`/api/tickets`)

| Method | Path | Response | Description |
|---|---|---|---|
| `GET` | `/api/tickets` | JSON `PageResponse` (`page=0`, `size=10`) | Current user's issued tickets (`qrToken` + `ticketCode`). |
| `GET` | `/api/tickets/{ticketId}` | JSON | Single ticket belonging to current user. |
| `GET` | `/api/tickets/{ticketId}/qr` | `image/png` | Server-side ZXing QR code PNG encoding signed `qrToken`. |
| `GET` | `/api/tickets/{ticketId}/pdf` | `application/pdf` | Server-side OpenPDF printable ticket attachment with embedded QR image. |

---

## 7. Door Staff & Check-In (`/api/staff`, `/api/events/{eventId}/checkin`)

Requires user to be assigned as staff (`STAFF`) on `{eventId}` (or be the event's `ORGANIZER`).

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/staff/events` | Events the authenticated user is assigned to scan at the door. |
| `POST` | `/api/events/{eventId}/checkin` | Atomic idempotent check-in via `{ "qrToken": "..." }` OR `{ "ticketCode": "GK-..." }`. Returns `200` on first scan, `409 Conflict` (`"Already checked in at <time> by <staff>"`) on duplicate/concurrent scans. |
| `GET` | `/api/events/{eventId}/checkin/recent` | Paginated recent scan audit logs (`page=0`, `size=20`). |

Example `POST /api/events/{eventId}/checkin` (`409` duplicate scan error):
```json
{
  "message": "Already checked in at 2026-12-01T17:45:12Z by Door Staff",
  "fieldErrors": {},
  "traceId": "8a91c0d4e21b334f"
}
```

---

## 8. Waitlist & Notifications (`/api/waitlist`, `/api/notifications`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/events/{eventId}/waitlist` | Join waitlist (`{ "ticketTypeId": "...", "quantity": 1 }`) when tier is sold out. |
| `GET` | `/api/waitlist/me` | Current user's paginated waitlist entries. |
| `DELETE` | `/api/waitlist/{entryId}` | Leave waitlist. |
| `GET` | `/api/notifications` | Paginated in-app notifications (`unreadOnly=false`, returns `unreadCount` + page). |
| `POST` | `/api/notifications/{notificationId}/read` | Mark single notification read. |
| `POST` | `/api/notifications/read-all` | Mark all unread notifications read. |

