import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { ScannerPanel } from "./ScannerPanel";
import { EventSummary } from "@/lib/types";

vi.mock("@zxing/browser", () => ({
  BrowserQRCodeReader: class {
    async decodeFromConstraints() {
      return { stop: vi.fn() };
    }
  },
}));

const mockEvents: EventSummary[] = [
  {
    id: "11111111-1111-1111-1111-111111111111",
    organizerId: "org-1",
    title: "GateKeeper Door Test",
    category: "Tech",
    venueName: "Main Hall",
    venueAddress: "1 Main St",
    venueCapacity: 100,
    startTime: new Date().toISOString(),
    endTime: new Date(Date.now() + 7200_000).toISOString(),
    timezone: "UTC",
    status: "PUBLISHED",
    waitingRoomEnabled: false,
    minPriceCents: 0,
    totalAvailable: 50,
  },
];

describe("ScannerPanel", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("admits valid ticket code and shows clear rejection on duplicate scan", async () => {
    const fetchMock = vi
      .fn()
      // initial recent logs
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({ items: [], page: 0, size: 15, totalElements: 0, totalPages: 0 }),
      })
      // first check-in succeeds
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          ticketId: "t1",
          eventId: mockEvents[0].id,
          ticketCode: "GK-ABCD1234",
          ticketTypeName: "VIP",
          attendeeName: "Alice Smith",
          status: "CHECKED_IN",
          checkedInAt: new Date().toISOString(),
          checkedInBy: "staff-1",
          checkedInByName: "Bob Staff",
        }),
      })
      // logs refresh
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({ items: [], page: 0, size: 15, totalElements: 0, totalPages: 0 }),
      })
      // duplicate scan rejected
      .mockResolvedValueOnce({
        ok: false,
        status: 409,
        headers: new Headers(),
        json: async () => ({
          message: "Already checked in at 2026-11-10T18:00:00Z by Bob Staff",
          fieldErrors: {},
          traceId: "tr-1",
        }),
      })
      // logs refresh
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({ items: [], page: 0, size: 15, totalElements: 0, totalPages: 0 }),
      });

    vi.stubGlobal("fetch", fetchMock);

    render(<ScannerPanel assignedEvents={mockEvents} />);

    const input = screen.getByLabelText(/Manual Fallback/i);
    fireEvent.change(input, { target: { value: "GK-ABCD1234" } });
    fireEvent.click(screen.getByRole("button", { name: /Verify & Check In/i }));

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/ADMITTED — CHECKED IN/i);
      expect(screen.getByRole("status")).toHaveTextContent(/Alice Smith/i);
    });

    fireEvent.change(input, { target: { value: "GK-ABCD1234" } });
    fireEvent.click(screen.getByRole("button", { name: /Verify & Check In/i }));

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/ENTRY REJECTED/i);
      expect(screen.getByRole("status")).toHaveTextContent(/Already checked in at/i);
    });
  });
});

