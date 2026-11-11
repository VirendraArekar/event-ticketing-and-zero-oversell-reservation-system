"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Clock } from "lucide-react";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { OrderResponse, PageResponse, WaitlistEntry } from "@/lib/types";
import { useAuth } from "@/context/AuthContext";
import { HoldCountdown } from "@/components/HoldCountdown";

export default function WaitlistPage() {
  const { user, loading: authLoading } = useAuth();
  const [entries, setEntries] = useState<PageResponse<WaitlistEntry> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const loadWaitlist = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<PageResponse<WaitlistEntry>>("/waitlist/me?page=0&size=20");
      setEntries(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load waitlist");
    } finally {
      setLoading(false);
    }
  }, [user]);

  useEffect(() => {
    if (authLoading || !user) return;
    let active = true;
    apiFetch<PageResponse<WaitlistEntry>>("/waitlist/me?page=0&size=20")
      .then((res) => {
        if (active) {
          setEntries(res);
          setError(null);
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load waitlist");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user]);

  const handleConfirmOffer = async (offerOrderId: string) => {
    setBusyId(offerOrderId);
    setError(null);
    try {
      await apiFetch<OrderResponse>(`/orders/${offerOrderId}/confirm`, { method: "POST" });
      await loadWaitlist();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not confirm offered order");
    } finally {
      setBusyId(null);
    }
  };

  const handleCancelEntry = async (entryId: string) => {
    setBusyId(entryId);
    setError(null);
    try {
      await apiFetch<void>(`/waitlist/${entryId}`, { method: "DELETE" });
      await loadWaitlist();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not leave waitlist");
    } finally {
      setBusyId(null);
    }
  };

  if (!authLoading && !user) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-8 text-center">
        <p className="text-slate-700">Please sign in to view your waitlist entries.</p>
        <Link href="/login" className="mt-3 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white">
          Sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-slate-900">My Waitlist</h1>

      {error && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="h-40 animate-pulse rounded-xl bg-white p-6" />
      ) : entries && entries.items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-12 text-center">
          <Clock className="mx-auto h-10 w-10 text-slate-400" />
          <p className="mt-3 font-semibold text-slate-900">No waitlist entries</p>
          <p className="mt-1 text-sm text-slate-500">
            When a ticket tier sells out, you can join its waitlist from the event page.
          </p>
        </div>
      ) : (
        <div className="space-y-4">
          {entries?.items.map((entry) => (
            <div key={entry.id} className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <Link href={`/events/${entry.eventId}`} className="text-lg font-bold text-slate-900 hover:text-blue-600">
                    {entry.eventTitle}
                  </Link>
                  <p className="text-sm text-slate-600">
                    {entry.quantity} × {entry.ticketTypeName}
                    {entry.status === "WAITING" && ` • Queue position #${entry.position}`}
                  </p>
                  <p className="text-xs text-slate-400">Joined {formatDateTime(entry.createdAt)}</p>
                </div>
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-800">
                  {entry.status}
                </span>
              </div>

              {entry.status === "OFFERED" && entry.offerOrderId && entry.offerExpiresAt && (
                <div className="mt-4 space-y-3">
                  <HoldCountdown expiresAt={entry.offerExpiresAt} onExpire={loadWaitlist} />
                  <button
                    type="button"
                    disabled={busyId === entry.offerOrderId}
                    onClick={() => handleConfirmOffer(entry.offerOrderId!)}
                    className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-50"
                  >
                    Confirm Waitlist Hold Now
                  </button>
                </div>
              )}

              {entry.status === "WAITING" && (
                <div className="mt-4">
                  <button
                    type="button"
                    disabled={busyId === entry.id}
                    onClick={() => handleCancelEntry(entry.id)}
                    className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
                  >
                    Leave Waitlist
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
