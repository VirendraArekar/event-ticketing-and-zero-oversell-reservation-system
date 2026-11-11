"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ReceiptText } from "lucide-react";
import { apiFetch } from "@/lib/api";
import { formatCents, formatDateTime } from "@/lib/format";
import { OrderResponse, PageResponse } from "@/lib/types";
import { useAuth } from "@/context/AuthContext";
import { HoldCountdown } from "@/components/HoldCountdown";

export default function OrdersPage() {
  const { user, loading: authLoading } = useAuth();
  const [orders, setOrders] = useState<PageResponse<OrderResponse> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const loadOrders = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<PageResponse<OrderResponse>>(`/orders?page=${page}&size=10`);
      setOrders(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load orders");
    } finally {
      setLoading(false);
    }
  }, [user, page]);

  useEffect(() => {
    if (authLoading || !user) return;
    let active = true;
    apiFetch<PageResponse<OrderResponse>>(`/orders?page=${page}&size=10`)
      .then((res) => {
        if (active) {
          setOrders(res);
          setError(null);
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load orders");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user, page]);

  const handleAction = async (orderId: string, action: "confirm" | "cancel") => {
    setBusyId(orderId);
    setError(null);
    try {
      await apiFetch<OrderResponse>(`/orders/${orderId}/${action}`, { method: "POST" });
      await loadOrders();
    } catch (err) {
      setError(err instanceof Error ? err.message : `Could not ${action} order`);
    } finally {
      setBusyId(null);
    }
  };

  if (!authLoading && !user) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-8 text-center">
        <p className="text-slate-700">Please sign in to view your orders.</p>
        <Link href="/login" className="mt-3 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white">
          Sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-slate-900">My Orders</h1>
        <Link href="/tickets" className="text-sm font-semibold text-blue-600 hover:underline">
          View My Tickets →
        </Link>
      </div>

      {error && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="space-y-4">
          {[1, 2].map((i) => (
            <div key={i} className="h-36 animate-pulse rounded-xl bg-white p-6 shadow-sm" />
          ))}
        </div>
      ) : orders && orders.items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-12 text-center">
          <ReceiptText className="mx-auto h-10 w-10 text-slate-400" />
          <p className="mt-3 font-semibold text-slate-900">No orders yet</p>
          <p className="mt-1 text-sm text-slate-500">Explore published events to reserve tickets.</p>
          <Link href="/" className="mt-4 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white">
            Browse Events
          </Link>
        </div>
      ) : (
        <div className="space-y-4">
          {orders?.items.map((order) => (
            <div key={order.id} className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <Link href={`/events/${order.eventId}`} className="text-lg font-bold text-slate-900 hover:text-blue-600">
                    {order.eventTitle}
                  </Link>
                  <p className="text-xs text-slate-500">
                    Order #{order.id.slice(0, 8)} • Created {formatDateTime(order.createdAt)}
                  </p>
                </div>
                <div className="text-right">
                  <span className="inline-block rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-800">
                    {order.status}
                  </span>
                  <p className="mt-1 text-base font-bold text-slate-900">
                    {formatCents(order.totalCents, order.currency)}
                  </p>
                </div>
              </div>

              <ul className="mt-3 space-y-1 border-t border-slate-100 pt-3 text-sm text-slate-700">
                {order.items.map((item) => (
                  <li key={item.id} className="flex justify-between">
                    <span>
                      {item.quantity} × {item.ticketTypeName}
                    </span>
                    <span>{formatCents(item.lineTotalCents, order.currency)}</span>
                  </li>
                ))}
              </ul>

              {order.status === "HELD" && order.holdExpiresAt && (
                <div className="mt-4 space-y-3">
                  <HoldCountdown expiresAt={order.holdExpiresAt} onExpire={loadOrders} />
                  <div className="flex gap-2">
                    <button
                      type="button"
                      disabled={busyId === order.id}
                      onClick={() => handleAction(order.id, "confirm")}
                      className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-50"
                    >
                      Confirm Order
                    </button>
                    <button
                      type="button"
                      disabled={busyId === order.id}
                      onClick={() => handleAction(order.id, "cancel")}
                      className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                    >
                      Cancel Hold
                    </button>
                  </div>
                </div>
              )}

              {order.status === "PENDING_PAYMENT" && (
                <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
                  <span>
                    Offline payment pending. Deadline: <strong>{formatDateTime(order.paymentDeadlineAt)}</strong>
                  </span>
                  <button
                    type="button"
                    disabled={busyId === order.id}
                    onClick={() => handleAction(order.id, "cancel")}
                    className="rounded border border-amber-400 px-3 py-1 font-semibold hover:bg-amber-100"
                  >
                    Cancel Order
                  </button>
                </div>
              )}

              {order.status === "CONFIRMED" && (
                <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-3">
                  <Link href="/tickets" className="text-sm font-semibold text-blue-600 hover:underline">
                    Open QR Tickets & PDF →
                  </Link>
                  <button
                    type="button"
                    disabled={busyId === order.id}
                    onClick={() => handleAction(order.id, "cancel")}
                    className="rounded-lg border border-red-200 px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50"
                  >
                    Cancel & Refund
                  </button>
                </div>
              )}

              {order.ledger.length > 0 && (
                <details className="mt-4 border-t border-slate-100 pt-3 text-xs text-slate-600">
                  <summary className="cursor-pointer font-semibold text-slate-700">
                    Payment Ledger ({order.ledger.length} entries)
                  </summary>
                  <ul className="mt-2 space-y-1">
                    {order.ledger.map((entry) => (
                      <li key={entry.id} className="flex flex-wrap justify-between gap-2">
                        <span>
                          <strong>{entry.entryType}</strong> ({entry.provider}) — {entry.notes}
                        </span>
                        <span className="font-mono">{formatDateTime(entry.createdAt)}</span>
                      </li>
                    ))}
                  </ul>
                </details>
              )}
            </div>
          ))}

          {orders && orders.totalPages > 1 && (
            <div className="flex items-center justify-between pt-2 text-sm">
              <button
                type="button"
                disabled={page <= 0}
                onClick={() => setPage((p) => p - 1)}
                className="rounded border border-slate-300 px-3 py-1.5 disabled:opacity-40"
              >
                Previous
              </button>
              <span>
                Page {orders.page + 1} of {orders.totalPages}
              </span>
              <button
                type="button"
                disabled={page + 1 >= orders.totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="rounded border border-slate-300 px-3 py-1.5 disabled:opacity-40"
              >
                Next
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
