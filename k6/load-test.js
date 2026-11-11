import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://nginx";

const bookedSuccess = new Counter("gatekeeper_booked_success");
const soldOutRejected = new Counter("gatekeeper_sold_out_409");
const rateLimited = new Counter("gatekeeper_rate_limited_429");

export const options = {
  scenarios: {
    catalog_browse: {
      executor: "ramping-vus",
      exec: "browseCatalog",
      startVUs: 5,
      stages: [
        { duration: "10s", target: 25 },
        { duration: "20s", target: 40 },
        { duration: "5s", target: 0 },
      ],
    },
    booking_burst: {
      executor: "ramping-vus",
      exec: "bookTicket",
      startTime: "5s",
      startVUs: 5,
      stages: [
        { duration: "10s", target: 20 },
        { duration: "15s", target: 20 },
        { duration: "5s", target: 0 },
      ],
    },
  },
  thresholds: {
    "http_req_duration{scenario:catalog_browse}": ["p(95)<250"],
    "http_req_duration{scenario:booking_burst}": ["p(95)<800"],
    checks: ["rate>0.95"],
  },
};

export function setup() {
  const runId = `${Date.now()}-${Math.floor(Math.random() * 10000)}`;
  const orgEmail = `k6-org-${runId}@gatekeeper.local`;

  const orgReg = http.post(
    `${BASE_URL}/api/auth/register`,
    JSON.stringify({
      email: orgEmail,
      password: "StrongPassword!1",
      fullName: "K6 Organizer",
      roles: ["ORGANIZER", "ATTENDEE"],
    }),
    { headers: { "Content-Type": "application/json", "X-Forwarded-For": "10.99.0.1" } }
  );
  check(orgReg, { "organizer registered": (r) => r.status === 201 });
  const orgToken = orgReg.json("accessToken");

  const start = new Date(Date.now() + 3600 * 1000).toISOString();
  const end = new Date(Date.now() + 5 * 3600 * 1000).toISOString();
  const salesStart = new Date(Date.now() - 3600 * 1000).toISOString();
  const salesEnd = new Date(Date.now() + 4 * 3600 * 1000).toISOString();

  const evRes = http.post(
    `${BASE_URL}/api/organizer/events`,
    JSON.stringify({
      title: `K6 Load Test Summit ${runId}`,
      description: "Automated k6 performance benchmark event",
      category: "Technology",
      venueName: "Load Arena",
      venueAddress: "1 Benchmark Blvd",
      venueCapacity: 500,
      startTime: start,
      endTime: end,
      timezone: "UTC",
    }),
    { headers: { "Content-Type": "application/json", Authorization: `Bearer ${orgToken}` } }
  );
  check(evRes, { "event created": (r) => r.status === 201 });
  const eventId = evRes.json("id");

  const ttRes = http.post(
    `${BASE_URL}/api/organizer/events/${eventId}/ticket-types`,
    JSON.stringify({
      name: "Load Pass",
      priceCents: 0,
      currency: "USD",
      capacity: 300,
      perOrderLimit: 2,
      salesStart,
      salesEnd,
    }),
    { headers: { "Content-Type": "application/json", Authorization: `Bearer ${orgToken}` } }
  );
  check(ttRes, { "ticket type created": (r) => r.status === 201 });
  const ticketTypeId = ttRes.json("id");

  const pubRes = http.post(`${BASE_URL}/api/organizer/events/${eventId}/publish`, null, {
    headers: { Authorization: `Bearer ${orgToken}` },
  });
  check(pubRes, { "event published": (r) => r.status === 200 });

  const buyerTokens = [];
  for (let i = 0; i < 40; i++) {
    const bRes = http.post(
      `${BASE_URL}/api/auth/register`,
      JSON.stringify({
        email: `k6-buyer-${runId}-${i}@gatekeeper.local`,
        password: "StrongPassword!1",
        fullName: `K6 Buyer ${i}`,
        roles: ["ATTENDEE"],
      }),
      { headers: { "Content-Type": "application/json", "X-Forwarded-For": `10.99.1.${i + 1}` } }
    );
    if (bRes.status === 201) {
      buyerTokens.push(bRes.json("accessToken"));
    }
  }

  return { orgToken, eventId, ticketTypeId, buyerTokens };
}

export function browseCatalog(data) {
  const listRes = http.get(`${BASE_URL}/api/events?page=0&size=12`);
  check(listRes, { "list status 200": (r) => r.status === 200 });

  const detailRes = http.get(`${BASE_URL}/api/events/${data.eventId}`);
  check(detailRes, { "detail status 200": (r) => r.status === 200 });
  sleep(0.2);
}

export function bookTicket(data) {
  const idx = (__VU + __ITER) % data.buyerTokens.length;
  const token = data.buyerTokens[idx];
  const idemKey = `k6-${__VU}-${__ITER}-${Date.now()}`;

  const holdRes = http.post(
    `${BASE_URL}/api/orders/hold`,
    JSON.stringify({
      eventId: data.eventId,
      items: [{ ticketTypeId: data.ticketTypeId, quantity: 1 }],
    }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        "Idempotency-Key": idemKey,
        "X-Forwarded-For": `10.99.2.${(__VU % 200) + 1}`,
      },
    }
  );

  if (holdRes.status === 201) {
    const orderId = holdRes.json("id");
    const confirmRes = http.post(`${BASE_URL}/api/orders/${orderId}/confirm`, null, {
      headers: {
        Authorization: `Bearer ${token}`,
        "X-Forwarded-For": `10.99.2.${(__VU % 200) + 1}`,
      },
    });
    check(confirmRes, { "confirm 200": (r) => r.status === 200 });
    bookedSuccess.add(1);
  } else if (holdRes.status === 409) {
    soldOutRejected.add(1);
    check(holdRes, { "sold out 409 clean": (r) => r.status === 409 });
  } else if (holdRes.status === 429) {
    rateLimited.add(1);
  } else {
    check(holdRes, { "unexpected hold error": () => false });
  }
  sleep(0.15);
}

export function teardown(data) {
  const detailRes = http.get(`${BASE_URL}/api/organizer/events/${data.eventId}`, {
    headers: { Authorization: `Bearer ${data.orgToken}` },
  });
  if (detailRes.status === 200) {
    const tt = detailRes.json("ticketTypes")[0];
    console.log(
      `[k6 teardown] capacity=${tt.capacity}, sold=${tt.sold}, held=${tt.held}, available=${tt.available}`
    );
    check(tt, {
      "zero oversell invariant (sold + held <= capacity)": (t) => t.sold + t.held <= t.capacity,
    });
  }
}

