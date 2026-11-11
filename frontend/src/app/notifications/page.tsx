"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Bell, CheckCheck } from "lucide-react";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { NotificationPage } from "@/lib/types";
import { useAuth } from "@/context/AuthContext";

export default function NotificationsPage() {
  const { user, loading: authLoading } = useAuth();
  const [data, setData] = useState<NotificationPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadNotifications = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<NotificationPage>("/notifications?page=0&size=30");
      setData(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load notifications");
    } finally {
      setLoading(false);
    }
  }, [user]);

  useEffect(() => {
    if (authLoading || !user) return;
    let active = true;
    apiFetch<NotificationPage>("/notifications?page=0&size=30")
      .then((res) => {
        if (active) {
          setData(res);
          setError(null);
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load notifications");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user]);

  const markRead = async (id: string) => {
    try {
      await apiFetch(`/notifications/${id}/read`, { method: "POST" });
      await loadNotifications();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to mark read");
    }
  };

  const markAllRead = async () => {
    try {
      await apiFetch("/notifications/read-all", { method: "POST" });
      await loadNotifications();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to mark all read");
    }
  };

  if (!authLoading && !user) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-8 text-center">
        <p className="text-slate-700">Please sign in to view your notifications.</p>
        <Link href="/login" className="mt-3 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white">
          Sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-slate-900">Notifications</h1>
        {data && data.unreadCount > 0 && (
          <button
            type="button"
            onClick={markAllRead}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
          >
            <CheckCheck className="h-4 w-4" />
            Mark all read ({data.unreadCount})
          </button>
        )}
      </div>

      {error && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="h-40 animate-pulse rounded-xl bg-white p-6" />
      ) : data && data.items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-12 text-center">
          <Bell className="mx-auto h-10 w-10 text-slate-400" />
          <p className="mt-3 font-semibold text-slate-900">No notifications yet</p>
          <p className="mt-1 text-sm text-slate-500">
            Order confirmations, hold expiry warnings, waitlist alerts, and check-ins appear here.
          </p>
        </div>
      ) : (
        <div className="divide-y divide-slate-200 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
          {data?.items.map((n) => (
            <div
              key={n.id}
              className={`flex flex-col gap-2 p-4 sm:flex-row sm:items-center sm:justify-between ${
                n.read ? "bg-white" : "bg-blue-50/50"
              }`}
            >
              <div>
                <div className="flex items-center gap-2">
                  {!n.read && <span className="h-2 w-2 rounded-full bg-blue-600" aria-label="Unread" />}
                  <span className="rounded bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700">
                    {n.type}
                  </span>
                  <span className="text-xs text-slate-400">{formatDateTime(n.createdAt)}</span>
                </div>
                <p className="mt-1 font-semibold text-slate-900">{n.title}</p>
                <p className="text-sm text-slate-600">{n.message}</p>
              </div>
              {!n.read && (
                <button
                  type="button"
                  onClick={() => markRead(n.id)}
                  className="shrink-0 rounded-md border border-slate-300 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
                >
                  Mark read
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
