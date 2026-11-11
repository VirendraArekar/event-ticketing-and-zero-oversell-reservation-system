import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { CheckoutPanel } from "./CheckoutPanel";
import { EventDetail } from "@/lib/types";

const mockEvent: EventDetail = {
  id: "11111111-1111-1111-1111-111111111111",
  organizerId: "22222222-2222-2222-2222-222222222222",
  title: "Distributed Systems Conf",
  description: "Engineering conference",
  category: "Tech",
  venueName: "Hall A",
  venueAddress: "100 Main St",
  venueCapacity: 100,
  startTime: new Date(Date.now() + 86_400_000).toISOString(),
  endTime: new Date(Date.now() + 90_000_000).toISOString(),
  timezone: "UTC",
  status: "PUBLISHED",
  cancellationCutoffAt: null,
  waitingRoomEnabled: false,
  waitingRoomThreshold: 25,
  version: 1,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  ticketTypes: [
    {
      id: "aaaa1111-1111-1111-1111-111111111111",
      eventId: "11111111-1111-1111-1111-111111111111",
      name: "General Admission",
      description: "Free community pass",
      priceCents: 0,
      currency: "USD",
      capacity: 50,
      held: 0,
      sold: 47,
      available: 3,
      perOrderLimit: 2,
      salesStart: new Date(Date.now() - 3600_000).toISOString(),
      salesEnd: new Date(Date.now() + 86_400_000).toISOString(),
    },
    {
      id: "bbbb2222-2222-2222-2222-222222222222",
      eventId: "11111111-1111-1111-1111-111111111111",
      name: "VIP",
      description: "Front row",
      priceCents: 5000,
      currency: "USD",
      capacity: 10,
      held: 0,
      sold: 10,
      available: 0,
      perOrderLimit: 2,
      salesStart: new Date(Date.now() - 3600_000).toISOString(),
      salesEnd: new Date(Date.now() + 86_400_000).toISOString(),
    },
  ],
};

describe("CheckoutPanel", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("displays low-stock and sold-out states, places hold with timer, and confirms order", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: new Headers(),
        json: async () => ({
          id: "ord-12345678",
          userId: "u1",
          eventId: mockEvent.id,
          eventTitle: mockEvent.title,
          status: "HELD",
          totalCents: 0,
          currency: "USD",
          holdExpiresAt: new Date(Date.now() + 600_000).toISOString(),
          paymentDeadlineAt: null,
          confirmedAt: null,
          cancelledAt: null,
          createdAt: new Date().toISOString(),
          items: [
            {
              id: "item-1",
              ticketTypeId: mockEvent.ticketTypes[0].id,
              ticketTypeName: "General Admission",
              quantity: 1,
              unitPriceCents: 0,
              lineTotalCents: 0,
            },
          ],
          ledger: [],
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          id: "ord-12345678",
          userId: "u1",
          eventId: mockEvent.id,
          eventTitle: mockEvent.title,
          status: "CONFIRMED",
          totalCents: 0,
          currency: "USD",
          holdExpiresAt: null,
          paymentDeadlineAt: null,
          confirmedAt: new Date().toISOString(),
          cancelledAt: null,
          createdAt: new Date().toISOString(),
          items: [
            {
              id: "item-1",
              ticketTypeId: mockEvent.ticketTypes[0].id,
              ticketTypeName: "General Admission",
              quantity: 1,
              unitPriceCents: 0,
              lineTotalCents: 0,
            },
          ],
          ledger: [],
        }),
      });

    vi.stubGlobal("fetch", fetchMock);

    render(<CheckoutPanel event={mockEvent} isAuthenticated={true} />);

    expect(screen.getByText("Only 3 left")).toBeInTheDocument();
    expect(screen.getByText("Sold out")).toBeInTheDocument();

    const incBtn = screen.getByRole("button", { name: "Increase General Admission quantity" });
    fireEvent.click(incBtn);

    const reserveBtn = screen.getByRole("button", { name: /Reserve Seats/i });
    fireEvent.click(reserveBtn);

    await waitFor(() => {
      expect(screen.getByRole("timer")).toHaveTextContent(/Seats held/);
    });

    const holdCallHeaders = fetchMock.mock.calls[0][1].headers as Headers;
    expect(holdCallHeaders.get("Idempotency-Key")).toBeTruthy();

    const confirmBtn = screen.getByRole("button", { name: /Confirm Free Tickets/i });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(screen.getByText(/Order Confirmed! Tickets Issued/i)).toBeInTheDocument();
    });
  });

  it("allows joining waitlist on sold-out ticket types", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 201,
      headers: new Headers(),
      json: async () => ({
        id: "wl-1",
        eventId: mockEvent.id,
        eventTitle: mockEvent.title,
        ticketTypeId: mockEvent.ticketTypes[1].id,
        ticketTypeName: "VIP",
        userId: "u1",
        quantity: 1,
        status: "WAITING",
        position: 2,
        offerOrderId: null,
        offeredAt: null,
        offerExpiresAt: null,
        createdAt: new Date().toISOString(),
      }),
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<CheckoutPanel event={mockEvent} isAuthenticated={true} />);
    fireEvent.click(screen.getByRole("button", { name: "Join Waitlist" }));

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/queue position #2/i);
    });
  });
});

