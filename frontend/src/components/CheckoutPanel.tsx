"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { AlertCircle, CheckCircle2, Hourglass, Ticket, Users } from "lucide-react";
import { apiFetch, ApiError } from "@/lib/api";
import { formatCents, formatDateTime } from "@/lib/format";
import { EventDetail, OrderResponse, WaitingRoomStatus, WaitlistEntry } from "@/lib/types";
import { HoldCountdown } from "./HoldCountdown";

interface CheckoutPanelProps {
  event: EventDetail;
  isAuthenticated: boolean;
  onOrderUpdated?: () => void;
}

export function CheckoutPanel({ event, isAuthenticated, onOrderUpdated }: CheckoutPanelProps) {
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [activeOrder, setActiveOrder] = useState<OrderResponse | null>(null);
  const [waitingRoom, setWaitingRoom] = useState<WaitingRoomStatus | null>(null);
  const [waitlistMsg, setWaitlistMsg] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const idempotencyKeyRef = useRef<string | null>(null);

  const totalSelected = useMemo(
    () => Object.values(quantities).reduce((sum, q) => sum + (q || 0), 0),
    [quantities]
  );

  const estimatedTotalCents = useMemo(
    () =>
      event.ticketTypes.reduce(
        (sum, tt) => sum + (quantities[tt.id] || 0) * tt.priceCents,
        0
      ),
    [event.ticketTypes, quantities]
  );

  const updateQty = (ticketTypeId: string, nextQty: number, maxAllowed: number) => {
    idempotencyKeyRef.current = null;
    setError(null);
    const clamped = Math.max(0, Math.min(nextQty, maxAllowed));
    setQuantities((prev) => ({ ...prev, [ticketTypeId]: clamped }));
  };

  // Poll waiting room if currently QUEUED
  useEffect(() => {
    if (!waitingRoom || waitingRoom.status !== "QUEUED") return;
    let active = true;
    const pollMs = Math.max(1000, (waitingRoom.pollAfterSeconds || 2) * 1000);

    const timer = setInterval(async () => {
      try {
        const nextStatus = await apiFetch<WaitingRoomStatus>(
          `/events/${event.id}/waiting-room/status?token=${encodeURIComponent(waitingRoom.queueToken)}`
        );
        if (!active) return;
        setWaitingRoom(nextStatus);
        if (nextStatus.status === "ADMITTED") {
          clearInterval(timer);
        }
      } catch (err) {
        if (!active) return;
        setError(err instanceof Error ? err.message : "Waiting room check failed");
        setWaitingRoom(null);
      }
    }, pollMs);

    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [waitingRoom, event.id]);

  const handleReserve = async () => {
    if (!isAuthenticated) {
      setError("Please sign in to reserve tickets.");
      return;
    }
    const items = Object.entries(quantities)
      .filter(([, qty]) => qty > 0)
      .map(([ticketTypeId, quantity]) => ({ ticketTypeId, quantity }));
    if (items.length === 0) {
      setError("Select at least one ticket to continue.");
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      let wrToken = waitingRoom?.status === "ADMITTED" ? waitingRoom.queueToken : undefined;
      if (event.waitingRoomEnabled && !wrToken) {
        const wr = await apiFetch<WaitingRoomStatus>(`/events/${event.id}/waiting-room/enter`, {
          method: "POST",
        });
        setWaitingRoom(wr);
        if (wr.status === "QUEUED") {
          setSubmitting(false);
          return;
        }
        wrToken = wr.queueToken;
      }

      if (!idempotencyKeyRef.current) {
        idempotencyKeyRef.current =
          typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
            ? crypto.randomUUID()
            : `gk-${Date.now()}-${Math.random().toString(36).slice(2)}`;
      }

      const headers: Record<string, string> = {
        "Idempotency-Key": idempotencyKeyRef.current,
      };
      if (wrToken) {
        headers["X-Waiting-Room-Token"] = wrToken;
      }

      const order = await apiFetch<OrderResponse>("/orders/hold", {
        method: "POST",
        headers,
        body: JSON.stringify({ eventId: event.id, items }),
      });

      idempotencyKeyRef.current = null;
      setWaitingRoom(null);
      setActiveOrder(order);
      onOrderUpdated?.();
    } catch (err) {
      if (err instanceof ApiError && err.status === 428) {
        const token = err.fieldErrors.queueToken;
        const pos = Number.parseInt(err.fieldErrors.position || "1", 10);
        if (token) {
          setWaitingRoom({
            eventId: event.id,
            status: "QUEUED",
            queueToken: token,
            position: Number.isFinite(pos) ? pos : 1,
            estimatedWaitSeconds: (Number.isFinite(pos) ? pos : 1) * 2,
            pollAfterSeconds: 2,
          });
          return;
        }
      }
      setError(err instanceof Error ? err.message : "Could not reserve seats");
    } finally {
      setSubmitting(false);
    }
  };

  const handleConfirm = async () => {
    if (!activeOrder) return;
    setSubmitting(true);
    setError(null);
    try {
      const updated = await apiFetch<OrderResponse>(`/orders/${activeOrder.id}/confirm`, {
        method: "POST",
      });
      setActiveOrder(updated);
      onOrderUpdated?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not confirm order");
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancelHold = async () => {
    if (!activeOrder) return;
    setSubmitting(true);
    setError(null);
    try {
      const updated = await apiFetch<OrderResponse>(`/orders/${activeOrder.id}/cancel`, {
        method: "POST",
      });
      setActiveOrder(updated);
      onOrderUpdated?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not cancel hold");
    } finally {
      setSubmitting(false);
    }
  };

  const handleJoinWaitlist = async (ticketTypeId: string, ticketTypeName: string) => {
    if (!isAuthenticated) {
      setError("Please sign in to join the waitlist.");
      return;
    }
    setSubmitting(true);
    setError(null);
    setWaitlistMsg(null);
    try {
      const entry = await apiFetch<WaitlistEntry>(`/events/${event.id}/waitlist`, {
        method: "POST",
        body: JSON.stringify({ ticketTypeId, quantity: 1 }),
      });
      setWaitlistMsg(
        `Joined waitlist for ${ticketTypeName} (queue position #${entry.position}). You will receive an in-app notification when a seat opens.`
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not join waitlist");
    } finally {
      setSubmitting(false);
    }
  };

  if (event.status !== "PUBLISHED") {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-6">
        <p className="text-sm font-medium text-slate-600">
          Ticket sales are unavailable because this event is currently <strong>{event.status}</strong>.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-slate-900">Select Tickets</h2>
        {event.waitingRoomEnabled && (
          <span className="inline-flex items-center gap-1 rounded-full bg-indigo-50 px-2.5 py-1 text-xs font-medium text-indigo-700">
            <Users className="h-3.5 w-3.5" />
            Waiting Room Enabled
          </span>
        )}
      </div>

      {error && (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {waitlistMsg && (
        <div
          role="status"
          className="flex items-start gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800"
        >
          <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{waitlistMsg}</span>
        </div>
      )}

      {waitingRoom && waitingRoom.status === "QUEUED" && (
        <div
          role="status"
          aria-live="polite"
          className="space-y-2 rounded-lg border border-indigo-200 bg-indigo-50 p-4 text-sm text-indigo-900"
        >
          <div className="flex items-center gap-2 font-semibold">
            <Hourglass className="h-4 w-4 animate-pulse" />
            <span>Virtual Waiting Room — Queue Position #{waitingRoom.position}</span>
          </div>
          <p className="text-xs text-indigo-700">
            High demand detected. Stay on this page; your spot refreshes automatically every{" "}
            {waitingRoom.pollAfterSeconds}s.
          </p>
        </div>
      )}

      {waitingRoom && waitingRoom.status === "ADMITTED" && !activeOrder && (
        <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">
          You are admitted from the waiting room. Click <strong>Reserve Seats</strong> now!
        </div>
      )}

      {!activeOrder ? (
        <>
          <div className="divide-y divide-slate-200">
            {event.ticketTypes.map((tt) => {
              const soldOut = tt.available <= 0;
              const maxAllowed = Math.min(tt.perOrderLimit, tt.available);
              const qty = quantities[tt.id] || 0;
              const lowStock = !soldOut && tt.available <= 5;
              return (
                <div key={tt.id} className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center sm:justify-between">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-slate-900">{tt.name}</span>
                      {soldOut ? (
                        <span className="rounded-full bg-red-100 px-2 py-0.5 text-xs font-semibold text-red-700">
                          Sold out
                        </span>
                      ) : lowStock ? (
                        <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">
                          Only {tt.available} left
                        </span>
                      ) : (
                        <span className="text-xs text-slate-500">{tt.available} available</span>
                      )}
                    </div>
                    {tt.description && <p className="mt-1 text-xs text-slate-600">{tt.description}</p>}
                    <p className="mt-1 text-sm font-medium text-slate-800">
                      {formatCents(tt.priceCents, tt.currency)}
                      <span className="ml-2 text-xs font-normal text-slate-500">
                        (Max {tt.perOrderLimit} per order)
                      </span>
                    </p>
                  </div>

                  {soldOut ? (
                    <button
                      type="button"
                      disabled={submitting}
                      onClick={() => handleJoinWaitlist(tt.id, tt.name)}
                      className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                    >
                      Join Waitlist
                    </button>
                  ) : (
                    <div className="flex items-center gap-2">
                      <label htmlFor={`qty-${tt.id}`} className="sr-only">
                        Quantity for {tt.name}
                      </label>
                      <button
                        type="button"
                        aria-label={`Decrease ${tt.name} quantity`}
                        onClick={() => updateQty(tt.id, qty - 1, maxAllowed)}
                        disabled={qty <= 0 || submitting}
                        className="h-8 w-8 rounded-md border border-slate-300 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-40"
                      >
                        -
                      </button>
                      <input
                        id={`qty-${tt.id}`}
                        type="number"
                        min={0}
                        max={maxAllowed}
                        value={qty}
                        onChange={(e) => updateQty(tt.id, Number.parseInt(e.target.value || "0", 10), maxAllowed)}
                        className="w-14 rounded-md border border-slate-300 py-1 text-center text-sm font-medium"
                      />
                      <button
                        type="button"
                        aria-label={`Increase ${tt.name} quantity`}
                        onClick={() => updateQty(tt.id, qty + 1, maxAllowed)}
                        disabled={qty >= maxAllowed || submitting}
                        className="h-8 w-8 rounded-md border border-slate-300 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-40"
                      >
                        +
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          <div className="border-t border-slate-200 pt-4">
            <div className="mb-3 flex items-center justify-between text-sm">
              <span className="text-slate-600">
                Total ({totalSelected} {totalSelected === 1 ? "seat" : "seats"})
              </span>
              <span className="text-lg font-bold text-slate-900">
                {totalSelected > 0 ? formatCents(estimatedTotalCents, event.ticketTypes[0]?.currency) : "—"}
              </span>
            </div>

            {isAuthenticated ? (
              <button
                type="button"
                disabled={submitting || totalSelected <= 0}
                onClick={handleReserve}
                className="w-full rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {submitting ? "Reserving seats..." : "Reserve Seats (10 min hold)"}
              </button>
            ) : (
              <Link
                href="/login"
                className="block w-full rounded-lg bg-blue-600 px-4 py-2.5 text-center text-sm font-semibold text-white hover:bg-blue-700"
              >
                Sign in to Reserve Seats
              </Link>
            )}
          </div>
        </>
      ) : (
        <div className="space-y-4">
          <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Order #{activeOrder.id.slice(0, 8)}
              </span>
              <span className="rounded-full bg-slate-900 px-2.5 py-0.5 text-xs font-semibold text-white">
                {activeOrder.status}
              </span>
            </div>
            <ul className="mt-2 space-y-1 text-sm text-slate-700">
              {activeOrder.items.map((item) => (
                <li key={item.id} className="flex justify-between">
                  <span>
                    {item.quantity} x {item.ticketTypeName}
                  </span>
                  <span className="font-medium">{formatCents(item.lineTotalCents, activeOrder.currency)}</span>
                </li>
              ))}
            </ul>
            <div className="mt-3 flex justify-between border-t border-slate-200 pt-2 font-semibold text-slate-900">
              <span>Total</span>
              <span>{formatCents(activeOrder.totalCents, activeOrder.currency)}</span>
            </div>
          </div>

          {activeOrder.status === "HELD" && activeOrder.holdExpiresAt && (
            <>
              <HoldCountdown
                expiresAt={activeOrder.holdExpiresAt}
                onExpire={() => {
                  setActiveOrder((prev) => (prev ? { ...prev, status: "EXPIRED" } : prev));
                  onOrderUpdated?.();
                }}
              />
              <div className="flex gap-2">
                <button
                  type="button"
                  disabled={submitting}
                  onClick={handleConfirm}
                  className="flex-1 rounded-lg bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-50"
                >
                  {submitting
                    ? "Confirming..."
                    : activeOrder.totalCents === 0
                    ? "Confirm Free Tickets"
                    : "Confirm Order (Offline Payment)"}
                </button>
                <button
                  type="button"
                  disabled={submitting}
                  onClick={handleCancelHold}
                  className="rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
                >
                  Release
                </button>
              </div>
            </>
          )}

          {activeOrder.status === "PENDING_PAYMENT" && (
            <div className="space-y-3 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
              <p className="font-semibold">Awaiting Offline Payment Verification</p>
              <p className="text-xs">
                Your seats are reserved until{" "}
                <strong>{formatDateTime(activeOrder.paymentDeadlineAt, event.timezone)}</strong>. An organizer will
                mark this order paid once offline payment is received, issuing your QR tickets automatically.
              </p>
              <Link
                href="/orders"
                className="inline-block text-xs font-semibold text-amber-900 underline"
              >
                View in My Orders →
              </Link>
            </div>
          )}

          {activeOrder.status === "CONFIRMED" && (
            <div className="space-y-3 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-900">
              <div className="flex items-center gap-2 font-semibold">
                <Ticket className="h-4 w-4" />
                <span>Order Confirmed! Tickets Issued</span>
              </div>
              <Link
                href="/tickets"
                className="inline-flex items-center gap-1 rounded-md bg-emerald-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-emerald-800"
              >
                View QR Tickets & Download PDF →
              </Link>
            </div>
          )}

          {(activeOrder.status === "CANCELLED" || activeOrder.status === "EXPIRED") && (
            <button
              type="button"
              onClick={() => setActiveOrder(null)}
              className="w-full rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Start a New Selection
            </button>
          )}
        </div>
      )}
    </div>
  );
}

