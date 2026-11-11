"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { apiFetch } from "@/lib/api";
import { EventSummary } from "@/lib/types";
import { useAuth } from "@/context/AuthContext";
import { ScannerPanel } from "@/components/ScannerPanel";

export default function ScannerPage() {
  const { user, loading: authLoading } = useAuth();
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (authLoading || !user) return;
    let active = true;
    apiFetch<EventSummary[]>("/staff/events")
      .then((res) => {
        if (active) {
          setEvents(res);
          setError(null);
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load staff events");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user]);

  if (!authLoading && !user) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-8 text-center">
        <p className="text-slate-700">Please sign in with your assigned door staff account.</p>
        <Link href="/login" className="mt-3 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white">
          Sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">Door Check-In Scanner</h1>
        <p className="text-sm text-slate-600">
          Scan attendee QR codes with your device camera or enter a ticket code manually.
        </p>
      </div>

      {error && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="h-52 animate-pulse rounded-xl bg-white p-6" />
      ) : (
        <ScannerPanel assignedEvents={events} />
      )}
    </div>
  );
}
