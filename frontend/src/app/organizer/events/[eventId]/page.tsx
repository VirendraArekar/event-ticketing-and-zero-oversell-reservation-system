"use client";

import React, { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, Download, UserPlus, Trash2, CheckCircle2 } from "lucide-react";
import { apiFetch, apiFetchBlob } from "@/lib/api";
import { formatCents, formatDateTime } from "@/lib/format";
import {
  AttendeeRecord,
  DashboardResponse,
  EventDetail,
  OrderResponse,
  PageResponse,
  StaffAssignment,
  WaitlistEntry,
} from "@/lib/types";
import { useAuth } from "@/context/AuthContext";
import { CheckInCurveChart, SalesOverTimeChart } from "@/components/DashboardCharts";

function toLocalInput(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export default function OrganizerEventManagePage() {
  const params = useParams<{ eventId: string }>();
  const eventId = params?.eventId;
  const { user, loading: authLoading } = useAuth();

  const [event, setEvent] = useState<EventDetail | null>(null);
  const [dashboard, setDashboard] = useState<DashboardResponse | null>(null);
  const [attendees, setAttendees] = useState<PageResponse<AttendeeRecord> | null>(null);
  const [orders, setOrders] = useState<PageResponse<OrderResponse> | null>(null);
  const [staff, setStaff] = useState<StaffAssignment[]>([]);
  const [waitlist, setWaitlist] = useState<PageResponse<WaitlistEntry> | null>(null);

  const [tab, setTab] = useState<"dashboard" | "tickets" | "orders" | "attendees" | "staff" | "edit">("dashboard");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Ticket type creation state
  const [ttName, setTtName] = useState("");
  const [ttPriceDollars, setTtPriceDollars] = useState("0");
  const [ttCap, setTtCap] = useState(50);
  const [ttLimit, setTtLimit] = useState(4);
  const [ttSalesStart, setTtSalesStart] = useState(() =>
    toLocalInput(new Date(Date.now() - 10 * 60 * 1000).toISOString())
  );
  const [ttSalesEnd, setTtSalesEnd] = useState(() =>
    toLocalInput(new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString())
  );

  // Staff assignment state
  const [staffEmail, setStaffEmail] = useState("");

  // Edit event state
  const [editTitle, setEditTitle] = useState("");
  const [editDesc, setEditDesc] = useState("");
  const [editVenueCap, setEditVenueCap] = useState(100);
  const [editWrEnabled, setEditWrEnabled] = useState(false);

  const loadAll = useCallback(async () => {
    if (!eventId || !user?.roles.includes("ORGANIZER")) return;
    setError(null);
    try {
      const [ev, dash, att, ord, stf, wl] = await Promise.all([
        apiFetch<EventDetail>(`/organizer/events/${eventId}`),
        apiFetch<DashboardResponse>(`/organizer/events/${eventId}/dashboard`),
        apiFetch<PageResponse<AttendeeRecord>>(`/organizer/events/${eventId}/attendees?page=0&size=50`),
        apiFetch<PageResponse<OrderResponse>>(`/organizer/events/${eventId}/orders?page=0&size=50`),
        apiFetch<StaffAssignment[]>(`/organizer/events/${eventId}/staff`),
        apiFetch<PageResponse<WaitlistEntry>>(`/organizer/events/${eventId}/waitlist?page=0&size=50`),
      ]);
      setEvent(ev);
      setDashboard(dash);
      setAttendees(att);
      setOrders(ord);
      setStaff(stf);
      setWaitlist(wl);
      setEditTitle(ev.title);
      setEditDesc(ev.description);
      setEditVenueCap(ev.venueCapacity);
      setEditWrEnabled(ev.waitingRoomEnabled);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load organizer event data");
    } finally {
      setLoading(false);
    }
  }, [eventId, user]);

  useEffect(() => {
    if (authLoading || !eventId || !user?.roles.includes("ORGANIZER")) return;
    let active = true;
    Promise.all([
      apiFetch<EventDetail>(`/organizer/events/${eventId}`),
      apiFetch<DashboardResponse>(`/organizer/events/${eventId}/dashboard`),
      apiFetch<PageResponse<AttendeeRecord>>(`/organizer/events/${eventId}/attendees?page=0&size=50`),
      apiFetch<PageResponse<OrderResponse>>(`/organizer/events/${eventId}/orders?page=0&size=50`),
      apiFetch<StaffAssignment[]>(`/organizer/events/${eventId}/staff`),
      apiFetch<PageResponse<WaitlistEntry>>(`/organizer/events/${eventId}/waitlist?page=0&size=50`),
    ])
      .then(([ev, dash, att, ord, stf, wl]) => {
        if (!active) return;
        setEvent(ev);
        setDashboard(dash);
        setAttendees(att);
        setOrders(ord);
        setStaff(stf);
        setWaitlist(wl);
        setEditTitle(ev.title);
        setEditDesc(ev.description);
        setEditVenueCap(ev.venueCapacity);
        setEditWrEnabled(ev.waitingRoomEnabled);
        setError(null);
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load organizer event data");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, eventId, user]);

  const handleLifecycle = async (action: "publish" | "cancel" | "complete") => {
    if (!eventId) return;
    setError(null);
    setNotice(null);
    try {
      await apiFetch(`/organizer/events/${eventId}/${action}`, { method: "POST" });
      setNotice(`Event ${action}ed successfully.`);
      await loadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : `Could not ${action} event`);
    }
  };

  const handleCreateTicketType = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!eventId) return;
    setError(null);
    setNotice(null);
    const priceCents = Math.round(Number.parseFloat(ttPriceDollars || "0") * 100);
    try {
      await apiFetch(`/organizer/events/${eventId}/ticket-types`, {
        method: "POST",
        body: JSON.stringify({
          name: ttName,
          description: "",
          priceCents,
          currency: "USD",
          capacity: ttCap,
          perOrderLimit: ttLimit,
          salesStart: new Date(ttSalesStart).toISOString(),
          salesEnd: new Date(ttSalesEnd).toISOString(),
        }),
      });
      setTtName("");
      setNotice("Ticket tier created.");
      await loadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create ticket type");
    }
  };

  const handleAssignStaff = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!eventId || !staffEmail.trim()) return;
    setError(null);
    setNotice(null);
    try {
      await apiFetch(`/organizer/events/${eventId}/staff`, {
        method: "POST",
        body: JSON.stringify({ email: staffEmail.trim() }),
      });
      setStaffEmail("");
      setNotice("Staff assigned for door check-in.");
      await loadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not assign staff");
    }
  };

  const handleRemoveStaff = async (staffUserId: string) => {
    if (!eventId) return;
    setError(null);
    try {
      await apiFetch(`/organizer/events/${eventId}/staff/${staffUserId}`, { method: "DELETE" });
      await loadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not remove staff");
    }
  };

  const handleMarkPaid = async (orderId: string) => {
    if (!eventId) return;
    setError(null);
    setNotice(null);
    try {
      await apiFetch(`/organizer/events/${eventId}/orders/${orderId}/mark-paid`, {
        method: "POST",
        body: JSON.stringify({}),
      });
      setNotice("Order marked paid and QR tickets issued.");
      await loadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not mark order paid");
    }
  };

  const handleUpdateEvent = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!event) return;
    setError(null);
    setNotice(null);
    try {
      await apiFetch(`/organizer/events/${event.id}`, {
        method: "PUT",
        body: JSON.stringify({
          title: editTitle,
          description: editDesc,
          category: event.category,
          venueName: event.venueName,
          venueAddress: event.venueAddress,
          venueCapacity: editVenueCap,
          startTime: event.startTime,
          endTime: event.endTime,
          timezone: event.timezone,
          cancellationCutoffAt: event.cancellationCutoffAt,
          waitingRoomEnabled: editWrEnabled,
          waitingRoomThreshold: event.waitingRoomThreshold,
        }),
      });
      setNotice("Event details updated.");
      await loadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to update event");
    }
  };

  const handleExportCsv = async () => {
    if (!eventId) return;
    try {
      const blob = await apiFetchBlob(`/organizer/events/${eventId}/attendees/export`);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `attendees-${eventId}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err instanceof Error ? err.message : "CSV export failed");
    }
  };

  if (loading) {
    return <div className="h-72 animate-pulse rounded-xl bg-white p-8" />;
  }

  if (!event) {
    return (
      <div className="rounded-xl border border-red-200 bg-red-50 p-6 text-sm text-red-700">
        {error || "Event not found or unauthorized"}
      </div>
    );
  }

  const allocatedCapacity = event.ticketTypes.reduce((sum, t) => sum + t.capacity, 0);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <Link href="/organizer" className="inline-flex items-center gap-1 text-sm text-slate-600 hover:text-slate-900">
            <ArrowLeft className="h-4 w-4" />
            Organizer Studio
          </Link>
          <div className="mt-1 flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-bold text-slate-900">{event.title}</h1>
            <span className="rounded-full bg-slate-900 px-3 py-0.5 text-xs font-semibold text-white">
              {event.status}
            </span>
          </div>
          <p className="text-sm text-slate-500">
            {formatDateTime(event.startTime, event.timezone)} • {event.venueName}
          </p>
        </div>

        <div className="flex flex-wrap gap-2">
          {event.status === "DRAFT" && (
            <button
              type="button"
              onClick={() => handleLifecycle("publish")}
              className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700"
            >
              Publish Event
            </button>
          )}
          {event.status === "PUBLISHED" && (
            <>
              <Link
                href={`/events/${event.id}`}
                className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Public Page
              </Link>
              <button
                type="button"
                onClick={() => handleLifecycle("complete")}
                className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Mark Completed
              </button>
              <button
                type="button"
                onClick={() => handleLifecycle("cancel")}
                className="rounded-lg border border-red-300 px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50"
              >
                Cancel Event
              </button>
            </>
          )}
        </div>
      </div>

      {error && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}
      {notice && (
        <div role="status" className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800">
          <CheckCircle2 className="h-4 w-4" />
          <span>{notice}</span>
        </div>
      )}

      <div className="flex flex-wrap gap-2 border-b border-slate-200 pb-2">
        {(
          [
            ["dashboard", "Analytics Dashboard"],
            ["tickets", `Ticket Tiers (${event.ticketTypes.length})`],
            ["orders", `Orders (${orders?.totalElements ?? 0})`],
            ["attendees", `Attendees (${attendees?.totalElements ?? 0})`],
            ["staff", `Staff & Waitlist (${staff.length})`],
            ["edit", "Edit Event"],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={`rounded-lg px-3.5 py-2 text-sm font-medium transition ${
              tab === key ? "bg-blue-600 text-white" : "text-slate-600 hover:bg-slate-100"
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === "dashboard" && dashboard && (
        <div className="space-y-6">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
              <p className="text-xs font-semibold uppercase text-slate-500">Tickets Sold</p>
              <p className="mt-1 text-2xl font-bold text-slate-900">
                {dashboard.totalSold} / {dashboard.totalTicketCapacity || dashboard.venueCapacity}
              </p>
              <p className="mt-1 text-xs text-slate-500">{dashboard.capacityUsedPercent}% capacity used</p>
            </div>

            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
              <p className="text-xs font-semibold uppercase text-slate-500">Gross Revenue</p>
              <p className="mt-1 text-2xl font-bold text-slate-900">
                {dashboard.totalRevenueCents === 0 ? "$0.00" : formatCents(dashboard.totalRevenueCents)}
              </p>
              <p className="mt-1 text-xs text-slate-500">{dashboard.totalCancelled} refunded/cancelled</p>
            </div>

            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
              <p className="text-xs font-semibold uppercase text-slate-500">Checked In</p>
              <p className="mt-1 text-2xl font-bold text-emerald-700">{dashboard.totalCheckedIn}</p>
              <p className="mt-1 text-xs text-slate-500">{dashboard.checkInRatePercent}% check-in rate</p>
            </div>

            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
              <p className="text-xs font-semibold uppercase text-slate-500">No-Show Count</p>
              <p className="mt-1 text-2xl font-bold text-slate-900">{dashboard.noShowCount}</p>
              <p className="mt-1 text-xs text-slate-500">Sold tickets not yet checked in</p>
            </div>
          </div>

          <div className="grid gap-6 lg:grid-cols-2">
            <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
              <h2 className="mb-3 text-base font-semibold text-slate-900">Check-Ins per 15-Minute Interval</h2>
              <CheckInCurveChart data={dashboard.checkInCurve} timeZone={event.timezone} />
            </div>

            <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
              <h2 className="mb-3 text-base font-semibold text-slate-900">Sales Over Time</h2>
              <SalesOverTimeChart data={dashboard.salesOverTime} timeZone={event.timezone} />
            </div>
          </div>

          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="mb-4 text-base font-semibold text-slate-900">Top Ticket Types & Breakdown</h2>
            {dashboard.ticketTypes.length === 0 ? (
              <p className="text-sm text-slate-500">Publish event and confirm orders to populate pre-aggregated metrics.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-slate-200 text-xs uppercase text-slate-500">
                    <tr>
                      <th className="py-2 pr-4">Ticket Type</th>
                      <th className="py-2 pr-4">Sold / Capacity</th>
                      <th className="py-2 pr-4">Revenue</th>
                      <th className="py-2">Checked In</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {dashboard.ticketTypes.map((tt) => (
                      <tr key={tt.ticketTypeId}>
                        <td className="py-2.5 pr-4 font-semibold text-slate-900">{tt.name}</td>
                        <td className="py-2.5 pr-4">
                          {tt.soldCount} / {tt.capacity}
                        </td>
                        <td className="py-2.5 pr-4">
                          {tt.revenueCents === 0 ? "$0.00" : formatCents(tt.revenueCents)}
                        </td>
                        <td className="py-2.5">{tt.checkedInCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {tab === "tickets" && (
        <div className="grid gap-6 lg:grid-cols-2">
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="text-base font-semibold text-slate-900">
              Configured Ticket Tiers ({allocatedCapacity} / {event.venueCapacity} venue capacity)
            </h2>
            {event.ticketTypes.length === 0 ? (
              <p className="mt-3 text-sm text-slate-500">Add at least one ticket tier before publishing.</p>
            ) : (
              <ul className="mt-4 divide-y divide-slate-200">
                {event.ticketTypes.map((tt) => (
                  <li key={tt.id} className="py-3">
                    <div className="flex justify-between font-semibold text-slate-900">
                      <span>{tt.name}</span>
                      <span>{formatCents(tt.priceCents, tt.currency)}</span>
                    </div>
                    <p className="mt-1 text-xs text-slate-600">
                      Total: {tt.capacity} • Held: {tt.held} • Sold: {tt.sold} • Available: {tt.available} • Limit:{" "}
                      {tt.perOrderLimit}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <form onSubmit={handleCreateTicketType} className="space-y-3 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="text-base font-semibold text-slate-900">Add Ticket Type</h2>
            <div>
              <label htmlFor="tt-name" className="block text-sm font-medium text-slate-700">
                Name (e.g. General, VIP)
              </label>
              <input
                id="tt-name"
                required
                value={ttName}
                onChange={(e) => setTtName(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label htmlFor="tt-price" className="block text-sm font-medium text-slate-700">
                  Price (USD)
                </label>
                <input
                  id="tt-price"
                  type="number"
                  step="0.01"
                  min="0"
                  required
                  value={ttPriceDollars}
                  onChange={(e) => setTtPriceDollars(e.target.value)}
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label htmlFor="tt-cap" className="block text-sm font-medium text-slate-700">
                  Capacity
                </label>
                <input
                  id="tt-cap"
                  type="number"
                  min={1}
                  required
                  value={ttCap}
                  onChange={(e) => setTtCap(Number.parseInt(e.target.value || "1", 10))}
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label htmlFor="tt-limit" className="block text-sm font-medium text-slate-700">
                  Order Limit
                </label>
                <input
                  id="tt-limit"
                  type="number"
                  min={1}
                  required
                  value={ttLimit}
                  onChange={(e) => setTtLimit(Number.parseInt(e.target.value || "1", 10))}
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                />
              </div>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label htmlFor="tt-start" className="block text-sm font-medium text-slate-700">
                  Sales Start
                </label>
                <input
                  id="tt-start"
                  type="datetime-local"
                  required
                  value={ttSalesStart}
                  onChange={(e) => setTtSalesStart(e.target.value)}
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label htmlFor="tt-end" className="block text-sm font-medium text-slate-700">
                  Sales End
                </label>
                <input
                  id="tt-end"
                  type="datetime-local"
                  required
                  value={ttSalesEnd}
                  onChange={(e) => setTtSalesEnd(e.target.value)}
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                />
              </div>
            </div>
            <button
              type="submit"
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700"
            >
              Add Ticket Type
            </button>
          </form>
        </div>
      )}

      {tab === "orders" && (
        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-base font-semibold text-slate-900">Event Orders & Offline Payment Verification</h2>
          {!orders || orders.items.length === 0 ? (
            <p className="text-sm text-slate-500">No orders placed for this event yet.</p>
          ) : (
            <div className="divide-y divide-slate-200">
              {orders.items.map((ord) => (
                <div key={ord.id} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm">
                  <div>
                    <p className="font-semibold text-slate-900">
                      Order #{ord.id.slice(0, 8)} — {formatCents(ord.totalCents, ord.currency)}
                    </p>
                    <p className="text-xs text-slate-500">
                      Status: <strong>{ord.status}</strong> •{" "}
                      {ord.items.map((i) => `${i.quantity}x ${i.ticketTypeName}`).join(", ")}
                    </p>
                  </div>
                  {ord.status === "PENDING_PAYMENT" && (
                    <button
                      type="button"
                      onClick={() => handleMarkPaid(ord.id)}
                      className="rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-emerald-700"
                    >
                      Mark Offline Paid & Issue Tickets
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {tab === "attendees" && (
        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-base font-semibold text-slate-900">Issued Attendee Tickets</h2>
            <button
              type="button"
              onClick={handleExportCsv}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
            >
              <Download className="h-3.5 w-3.5" />
              Export CSV
            </button>
          </div>
          {!attendees || attendees.items.length === 0 ? (
            <p className="text-sm text-slate-500">No attendees issued tickets yet.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-200 text-xs uppercase text-slate-500">
                  <tr>
                    <th className="py-2 pr-4">Ticket Code</th>
                    <th className="py-2 pr-4">Attendee</th>
                    <th className="py-2 pr-4">Email</th>
                    <th className="py-2 pr-4">Tier</th>
                    <th className="py-2 pr-4">Status</th>
                    <th className="py-2">Checked In</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {attendees.items.map((att) => (
                    <tr key={att.ticketId}>
                      <td className="py-2 pr-4 font-mono text-xs">{att.ticketCode}</td>
                      <td className="py-2 pr-4 font-medium text-slate-900">{att.attendeeName}</td>
                      <td className="py-2 pr-4 text-slate-600">{att.attendeeEmail}</td>
                      <td className="py-2 pr-4">{att.ticketTypeName}</td>
                      <td className="py-2 pr-4">{att.status}</td>
                      <td className="py-2 text-xs text-slate-500">
                        {att.checkedInAt ? formatDateTime(att.checkedInAt, event.timezone) : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {tab === "staff" && (
        <div className="grid gap-6 lg:grid-cols-2">
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="text-base font-semibold text-slate-900">Door Check-In Staff</h2>
            <form onSubmit={handleAssignStaff} className="mt-3 flex gap-2">
              <input
                type="email"
                required
                placeholder="staff@example.com"
                value={staffEmail}
                onChange={(e) => setStaffEmail(e.target.value)}
                className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
              <button
                type="submit"
                className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700"
              >
                <UserPlus className="h-4 w-4" />
                Assign
              </button>
            </form>

            {staff.length === 0 ? (
              <p className="mt-4 text-sm text-slate-500">No door staff assigned yet.</p>
            ) : (
              <ul className="mt-4 divide-y divide-slate-200 text-sm">
                {staff.map((s) => (
                  <li key={s.id} className="flex items-center justify-between py-2.5">
                    <div>
                      <p className="font-semibold text-slate-900">{s.fullName}</p>
                      <p className="text-xs text-slate-500">{s.email}</p>
                    </div>
                    <button
                      type="button"
                      aria-label={`Remove ${s.email}`}
                      onClick={() => handleRemoveStaff(s.userId)}
                      className="rounded p-1.5 text-red-600 hover:bg-red-50"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="text-base font-semibold text-slate-900">Event Waitlist</h2>
            {!waitlist || waitlist.items.length === 0 ? (
              <p className="mt-3 text-sm text-slate-500">No users currently waitlisted.</p>
            ) : (
              <ul className="mt-3 divide-y divide-slate-200 text-sm">
                {waitlist.items.map((w) => (
                  <li key={w.id} className="flex items-center justify-between py-2">
                    <span>
                      {w.quantity} × {w.ticketTypeName}
                    </span>
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-semibold">{w.status}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}

      {tab === "edit" && (
        <form onSubmit={handleUpdateEvent} className="max-w-2xl space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="text-base font-semibold text-slate-900">Edit Event Details</h2>
          <div>
            <label htmlFor="edit-title" className="block text-sm font-medium text-slate-700">
              Title
            </label>
            <input
              id="edit-title"
              required
              value={editTitle}
              onChange={(e) => setEditTitle(e.target.value)}
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label htmlFor="edit-desc" className="block text-sm font-medium text-slate-700">
              Description
            </label>
            <textarea
              id="edit-desc"
              required
              rows={4}
              value={editDesc}
              onChange={(e) => setEditDesc(e.target.value)}
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label htmlFor="edit-cap" className="block text-sm font-medium text-slate-700">
              Venue Capacity
            </label>
            <input
              id="edit-cap"
              type="number"
              min={1}
              required
              value={editVenueCap}
              onChange={(e) => setEditVenueCap(Number.parseInt(e.target.value || "1", 10))}
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
            />
          </div>
          <label className="flex items-center gap-2 text-sm font-medium text-slate-700">
            <input
              type="checkbox"
              checked={editWrEnabled}
              onChange={(e) => setEditWrEnabled(e.target.checked)}
              className="h-4 w-4 rounded border-slate-300 text-blue-600"
            />
            Enable Virtual Waiting Room
          </label>
          <button
            type="submit"
            className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-semibold text-white hover:bg-blue-700"
          >
            Save Changes
          </button>
        </form>
      )}
    </div>
  );
}
