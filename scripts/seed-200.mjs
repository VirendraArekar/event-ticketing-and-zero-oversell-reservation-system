#!/usr/bin/env node
/**
 * Populates a running GateKeeper instance with 200+ events and full end-to-end
 * workflow data (users, ticket tiers, holds, offline confirmations, mark-paid settlements,
 * cancellations/refunds, QR tickets, door check-ins, waitlists, notifications, and
 * Kafka-aggregated organizer dashboards).
 *
 * Usage:
 *   BASE_URL=http://localhost node scripts/seed-200.mjs
 */

const BASE_URL = (process.env.BASE_URL || "http://localhost").replace(/\/$/, "");
const DEMO_PASSWORD = process.env.DEMO_PASSWORD || "GateKeeperDemo!1";
const TARGET_PUBLISHED_EVENTS = Number.parseInt(process.env.SEED_EVENTS || "200", 10);

const CATEGORIES = [
  "Technology",
  "Music",
  "Conference",
  "Sports",
  "Arts",
  "Business",
  "Food & Drink",
  "Gaming",
  "Education",
  "Community",
];

const VENUES = [
  { name: "Harbor Convention Center", addr: "100 Pier Way, San Francisco, CA", tz: "America/Los_Angeles" },
  { name: "Hudson Hall", addr: "450 W 33rd St, New York, NY", tz: "America/New_York" },
  { name: "Thames Pavilion", addr: "22 Riverbank Walk, London", tz: "Europe/London" },
  { name: "Shibuya Dome", addr: "2-15 Dogenzaka, Tokyo", tz: "Asia/Tokyo" },
  { name: "Indiranagar Stage", addr: "80 Feet Rd, Bengaluru", tz: "Asia/Kolkata" },
  { name: "Civic Amphitheater", addr: "700 Civic Plaza, Austin, TX", tz: "UTC" },
];

const ADJECTIVES = [
  "Global",
  "Summit",
  "NextGen",
  "Autumn",
  "Interactive",
  "Live",
  "Cloud",
  "Open",
  "Founders",
  "LateNight",
  "Creators",
  "Regional",
  "Annual",
  "Acoustic",
  "Midnight",
  "Edge",
  "Applied",
  "Civic",
  "Indie",
  "Premier",
];

const NOUNS = [
  "Expo",
  "Festival",
  "Forum",
  "Showcase",
  "Meetup",
  "Championship",
  "Workshop",
  "Symposium",
  "Gala",
  "Jam",
];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function api(path, { method = "GET", token = null, headers = {}, body = null, rawText = false } = {}) {
  for (let attempt = 0; attempt < 6; attempt++) {
    const reqHeaders = { ...headers };
    if (token) reqHeaders["Authorization"] = `Bearer ${token}`;
    if (body !== null && !reqHeaders["Content-Type"]) {
      reqHeaders["Content-Type"] = "application/json";
    }

    const res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: reqHeaders,
      body: body !== null ? JSON.stringify(body) : undefined,
    });

    if (res.status === 429) {
      const retrySec = Number.parseInt(res.headers.get("Retry-After") || "2", 10);
      await sleep(Math.max(500, retrySec * 1000));
      continue;
    }

    const text = await res.text();
    let data = null;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        data = text;
      }
    }

    if (!res.ok) {
      const msg = typeof data === "object" && data?.message ? data.message : text || `HTTP ${res.status}`;
      const err = new Error(`${method} ${path} failed (${res.status}): ${msg}`);
      err.status = res.status;
      err.payload = data;
      throw err;
    }
    return rawText ? text : data;
  }
  throw new Error(`Exceeded retry budget calling ${method} ${path}`);
}

async function ensureUser(email, fullName, roles, ipSuffix) {
  const clientIp = `10.200.${Math.floor(ipSuffix / 250)}.${(ipSuffix % 250) + 1}`;
  try {
    const res = await api("/api/auth/register", {
      method: "POST",
      headers: { "X-Forwarded-For": clientIp },
      body: { email, password: DEMO_PASSWORD, fullName, roles },
    });
    return { email, clientIp, token: res.accessToken, user: res.user };
  } catch (err) {
    if (err.status === 409) {
      const res = await api("/api/auth/login", {
        method: "POST",
        headers: { "X-Forwarded-For": clientIp },
        body: { email, password: DEMO_PASSWORD },
      });
      return { email, clientIp, token: res.accessToken, user: res.user };
    }
    throw err;
  }
}

async function main() {
  console.log(`Seeding GateKeeper at ${BASE_URL} with ${TARGET_PUBLISHED_EVENTS} published events + lifecycle showcase...`);

  // Ensure API gateway is up
  const existing = await api("/api/events?page=0&size=1");
  console.log(`Connected cleanly. Current public published events: ${existing.totalElements}`);

  // 1. Register Organizers, Door Staff, and Attendees
  const organizers = [];
  for (let i = 1; i <= 6; i++) {
    organizers.push(
      await ensureUser(`organizer${i}@gatekeeper.demo`, `Organizer ${i}`, ["ORGANIZER", "ATTENDEE"], i)
    );
  }

  const staffUsers = [];
  for (let i = 1; i <= 4; i++) {
    staffUsers.push(
      await ensureUser(`staff${i}@gatekeeper.demo`, `Door Staff ${i}`, ["ATTENDEE"], 20 + i)
    );
  }

  const attendees = [];
  for (let i = 1; i <= 30; i++) {
    attendees.push(
      await ensureUser(`attendee${i}@gatekeeper.demo`, `Attendee ${i}`, ["ATTENDEE"], 40 + i)
    );
  }

  console.log(`Accounts ready: ${organizers.length} organizers, ${staffUsers.length} staff, ${attendees.length} attendees.`);

  const nowMs = Date.now();
  const publishedEvents = [];

  // 2. Create TARGET_PUBLISHED_EVENTS published events
  for (let i = 0; i < TARGET_PUBLISHED_EVENTS; i++) {
    const org = organizers[i < 35 ? 0 : i % organizers.length];
    const category = CATEGORIES[i % CATEGORIES.length];
    const venue = VENUES[i % VENUES.length];
    const adj = ADJECTIVES[i % ADJECTIVES.length];
    const noun = NOUNS[Math.floor(i / ADJECTIVES.length) % NOUNS.length];
    const title = `${adj} ${category} ${noun} #${String(i + 1).padStart(3, "0")}`;

    // First 20 events start within +30..+110m so check-in sits inside earlyWindowMinutes (240m).
    // Cancellation cutoff must never exceed startTime; for idx >= 20 it stays comfortably in the future.
    let startMs;
    if (i < 20) {
      startMs = nowMs + (30 + (i % 5) * 20) * 60 * 1000;
    } else if (i < 40) {
      startMs = nowMs + (12 + (i - 20) * 3) * 3600 * 1000;
    } else {
      startMs = nowMs + (4 + (i - 40)) * 24 * 3600 * 1000;
    }
    const endMs = startMs + 4 * 3600 * 1000;
    const cutoffMs = i < 20 ? startMs - 15 * 60 * 1000 : startMs - 2 * 3600 * 1000;

    // Give a few events small capacity so they legitimately sell out and collect waitlist entries
    const isScarce = i >= 20 && i < 28;
    const tier1Cap = isScarce ? 2 : 60 + (i % 4) * 30;
    const tier2Cap = isScarce ? 4 : 25 + (i % 3) * 15;
    const venueCapacity = tier1Cap + tier2Cap + (isScarce ? 0 : 20);

    const salesStart = new Date(nowMs - 2 * 3600 * 1000).toISOString();
    const salesEnd = new Date(endMs).toISOString();

    const event = await api("/api/organizer/events", {
      method: "POST",
      token: org.token,
      body: {
        title,
        description: `${title} hosted at ${venue.name}. Real seeded workflow dataset covering discovery, seat holds, QR ticketing, and live door scanning.`,
        category,
        venueName: venue.name,
        venueAddress: venue.addr,
        venueCapacity,
        startTime: new Date(startMs).toISOString(),
        endTime: new Date(endMs).toISOString(),
        timezone: venue.tz,
        cancellationCutoffAt: new Date(cutoffMs).toISOString(),
        waitingRoomEnabled: i === 29,
        waitingRoomThreshold: 25,
      },
    });

    const freeEvent = i % 3 === 0;
    const t1Price = freeEvent ? 0 : (15 + (i % 6) * 10) * 100;
    const t2Price = (45 + (i % 5) * 25) * 100;

    const tier1 = await api(`/api/organizer/events/${event.id}/ticket-types`, {
      method: "POST",
      token: org.token,
      body: {
        name: freeEvent ? "Community Pass" : "General Admission",
        description: "Standard entry tier",
        priceCents: t1Price,
        currency: "USD",
        capacity: tier1Cap,
        perOrderLimit: isScarce ? 2 : 5,
        salesStart,
        salesEnd,
      },
    });

    const tier2 = await api(`/api/organizer/events/${event.id}/ticket-types`, {
      method: "POST",
      token: org.token,
      body: {
        name: "VIP Backstage",
        description: "Priority lane + lounge access",
        priceCents: t2Price,
        currency: "USD",
        capacity: tier2Cap,
        perOrderLimit: 4,
        salesStart,
        salesEnd,
      },
    });

    await api(`/api/organizer/events/${event.id}/publish`, { method: "POST", token: org.token });

    // Assign door staff to active-window events + organizer1's early events
    if (i < 25) {
      const staff = staffUsers[i % staffUsers.length];
      await api(`/api/organizer/events/${event.id}/staff`, {
        method: "POST",
        token: org.token,
        body: { email: staff.email },
      });
      publishedEvents.push({ event, org, staff, tiers: [tier1, tier2], inDoorWindow: i < 20, isScarce });
    } else {
      publishedEvents.push({ event, org, staff: staffUsers[0], tiers: [tier1, tier2], inDoorWindow: false, isScarce });
    }

    if ((i + 1) % 40 === 0) {
      console.log(`Created & published ${i + 1}/${TARGET_PUBLISHED_EVENTS} events...`);
    }
  }

  // 3. Create 12 additional lifecycle showcase events under organizer1 (DRAFT, COMPLETED, CANCELLED)
  const primaryOrg = organizers[0];
  for (let d = 0; d < 12; d++) {
    const venue = VENUES[d % VENUES.length];
    const startMs = nowMs + (d + 10) * 24 * 3600 * 1000;
    const ev = await api("/api/organizer/events", {
      method: "POST",
      token: primaryOrg.token,
      body: {
        title: `Organizer Lifecycle Showcase #${d + 1}`,
        description: "Showcase event demonstrating non-published lifecycle states in Organizer Studio.",
        category: CATEGORIES[d % CATEGORIES.length],
        venueName: venue.name,
        venueAddress: venue.addr,
        venueCapacity: 120,
        startTime: new Date(startMs).toISOString(),
        endTime: new Date(startMs + 3 * 3600 * 1000).toISOString(),
        timezone: venue.tz,
        cancellationCutoffAt: new Date(startMs - 3600 * 1000).toISOString(),
      },
    });

    await api(`/api/organizer/events/${ev.id}/ticket-types`, {
      method: "POST",
      token: primaryOrg.token,
      body: {
        name: "Standard Seat",
        description: "Showcase tier",
        priceCents: 2500,
        currency: "USD",
        capacity: 100,
        perOrderLimit: 4,
        salesStart: new Date(nowMs - 3600 * 1000).toISOString(),
        salesEnd: new Date(startMs).toISOString(),
      },
    });

    if (d >= 5 && d < 9) {
      await api(`/api/organizer/events/${ev.id}/publish`, { method: "POST", token: primaryOrg.token });
      await api(`/api/organizer/events/${ev.id}/complete`, { method: "POST", token: primaryOrg.token });
    } else if (d >= 9) {
      await api(`/api/organizer/events/${ev.id}/publish`, { method: "POST", token: primaryOrg.token });
      await api(`/api/organizer/events/${ev.id}/cancel`, { method: "POST", token: primaryOrg.token });
    }
  }

  // 4. Execute real bookings across the first 45 published events (free confirm, paid mark-paid, pending, cancelled, held, check-ins, waitlist)
  let orderCount = 0;
  let ticketCount = 0;
  let checkInCount = 0;
  let waitlistCount = 0;

  for (let idx = 0; idx < 45 && idx < publishedEvents.length; idx++) {
    const item = publishedEvents[idx];
    const primaryTier = item.tiers[0];
    const vipTier = item.tiers[1];

    if (item.isScarce) {
      // Sell out tier1 (capacity 2) and add 2 attendees to waitlist
      const buyer = attendees[idx % attendees.length];
      const hold = await api("/api/orders/hold", {
        method: "POST",
        token: buyer.token,
        headers: {
          "Idempotency-Key": `seed-scarce-${idx}`,
          "X-Forwarded-For": buyer.clientIp,
        },
        body: {
          eventId: item.event.id,
          items: [{ ticketTypeId: primaryTier.id, quantity: 2 }],
        },
      });
      orderCount++;
      const confirmed = await api(`/api/orders/${hold.id}/confirm`, {
        method: "POST",
        token: buyer.token,
        headers: { "X-Forwarded-For": buyer.clientIp },
      });
      if (confirmed.status === "PENDING_PAYMENT") {
        await api(`/api/organizer/events/${item.event.id}/orders/${hold.id}/mark-paid`, {
          method: "POST",
          token: item.org.token,
          body: { referenceCode: `CASH-${idx}`, notes: "Seeded offline settlement" },
        });
      }
      ticketCount += 2;

      for (let w = 1; w <= 2; w++) {
        const waiter = attendees[(idx + w) % attendees.length];
        await api(`/api/events/${item.event.id}/waitlist`, {
          method: "POST",
          token: waiter.token,
          headers: { "X-Forwarded-For": waiter.clientIp },
          body: { ticketTypeId: primaryTier.id, quantity: 1 },
        });
        waitlistCount++;
      }
      continue;
    }

    // Place 2-3 orders per active/featured event
    const buyersForEvent = item.inDoorWindow ? 3 : 2;
    for (let b = 0; b < buyersForEvent; b++) {
      const buyer = attendees[(idx * 3 + b) % attendees.length];
      const chosenTier = b === 1 ? vipTier : primaryTier;
      const qty = b === 0 ? 2 : 1;

      const hold = await api("/api/orders/hold", {
        method: "POST",
        token: buyer.token,
        headers: {
          "Idempotency-Key": `seed-ord-${idx}-${b}`,
          "X-Forwarded-For": buyer.clientIp,
        },
        body: {
          eventId: item.event.id,
          items: [{ ticketTypeId: chosenTier.id, quantity: qty }],
        },
      });
      orderCount++;

      // Leave a handful in HELD state so active 10-min countdown holds exist
      if (idx >= 36 && idx < 42 && b === 1) {
        continue;
      }

      const confirmed = await api(`/api/orders/${hold.id}/confirm`, {
        method: "POST",
        token: buyer.token,
        headers: { "X-Forwarded-For": buyer.clientIp },
      });

      if (confirmed.status === "PENDING_PAYMENT") {
        // Leave a few in PENDING_PAYMENT for Organizer "Mark Paid" testing
        if (idx >= 30 && idx < 36 && b === 1) {
          continue;
        }
        await api(`/api/organizer/events/${item.event.id}/orders/${hold.id}/mark-paid`, {
          method: "POST",
          token: item.org.token,
          body: { referenceCode: `INV-${idx}-${b}`, notes: "Settled offline during seed" },
        });
      }

      ticketCount += qty;

      // Cancel + refund a small batch of orders to populate refund ledger metrics
      if (idx >= 24 && idx < 29 && b === 1) {
        await api(`/api/orders/${hold.id}/cancel`, {
          method: "POST",
          token: buyer.token,
          headers: { "X-Forwarded-For": buyer.clientIp },
        });
        continue;
      }

      // Check in ~half of tickets for events currently inside the door scan window
      if (item.inDoorWindow) {
        const ticketsPage = await api("/api/tickets?page=0&size=50", { token: buyer.token });
        const matchingTickets = (ticketsPage.items || []).filter(
          (t) => t.orderId === hold.id && t.status === "VALID"
        );
        for (let tIdx = 0; tIdx < matchingTickets.length; tIdx++) {
          if (tIdx === 0 || idx % 2 === 0) {
            await api(`/api/events/${item.event.id}/checkin`, {
              method: "POST",
              token: item.staff.token,
              body: { qrToken: matchingTickets[tIdx].qrToken },
            });
            checkInCount++;
          }
        }
      }
    }
  }

  // Wait briefly for OutboxRelayJob + Kafka consumers to finish materializing dashboards & notifications
  console.log("Waiting for outbox -> Kafka consumers to synchronize dashboard aggregates...");
  await sleep(4000);

  const finalCatalog = await api("/api/events?page=0&size=1");
  console.log("\n=== GateKeeper Seed Complete ===");
  console.log(`Public published events : ${finalCatalog.totalElements}`);
  console.log(`Extra lifecycle events  : 12 (DRAFT / COMPLETED / CANCELLED under organizer1@gatekeeper.demo)`);
  console.log(`Orders placed           : ${orderCount}`);
  console.log(`Tickets issued          : ${ticketCount}`);
  console.log(`Door check-ins recorded : ${checkInCount}`);
  console.log(`Waitlist entries        : ${waitlistCount}`);
  console.log(`Demo account password   : ${DEMO_PASSWORD}`);
  console.log(`Sample Organizer login  : organizer1@gatekeeper.demo`);
  console.log(`Sample Door Staff login : staff1@gatekeeper.demo`);
  console.log(`Sample Attendee login   : attendee1@gatekeeper.demo`);
}

main().catch((err) => {
  console.error("Seed failed:", err);
  process.exit(1);
});

