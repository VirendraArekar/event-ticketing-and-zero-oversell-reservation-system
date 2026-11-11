"use client";

import React, { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, Calendar, MapPin, ShieldAlert } from "lucide-react";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { EventDetail } from "@/lib/types";
import { useAuth } from "@/context/AuthContext";
import { CheckoutPanel } from "@/components/CheckoutPanel";

export default function EventDetailPage() {
  const params = useParams<{ eventId: string }>();
  const eventId = params?.eventId;
  const { user } = useAuth();
  const [event, setEvent] = useState<EventDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadEvent = useCallback(async () => {
    if (!eventId) return;
    setError(null);
    try {
      const data = await apiFetch<EventDetail>(`/events/${eventId}`);
      setEvent(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load event");
    } finally {
      setLoading(false);
    }
  }, [eventId]);

  useEffect(() => {
    if (!eventId) return;
    let active = true;
    apiFetch<EventDetail>(`/events/${eventId}`)
      .then((data) => {
        if (active) {
          setEvent(data);
          setError(null);
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load event");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [eventId]);

  if (loading) {
    return <div className="h-72 animate-pulse rounded-xl bg-white p-8 shadow-sm" />;
  }

  if (error || !event) {
    return (
      <div className="rounded-xl border border-red-200 bg-red-50 p-6 text-sm text-red-700">
        <p>{error || "Event not found"}</p>
        <Link href="/" className="mt-3 inline-block font-semibold underline">
          ← Back to events
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <Link href="/" className="inline-flex items-center gap-1.5 text-sm font-medium text-slate-600 hover:text-slate-900">
        <ArrowLeft className="h-4 w-4" />
        Back to events
      </Link>

      <div className="grid gap-8 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold text-blue-700">
                {event.category}
              </span>
              <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-700">
                {event.status}
              </span>
            </div>

            <h1 className="mt-4 text-2xl font-bold text-slate-900 sm:text-3xl">{event.title}</h1>

            <div className="mt-5 grid gap-4 border-y border-slate-200 py-4 text-sm text-slate-700 sm:grid-cols-2">
              <div className="flex items-start gap-2.5">
                <Calendar className="mt-0.5 h-4 w-4 shrink-0 text-blue-600" />
                <div>
                  <p className="font-semibold text-slate-900">Starts</p>
                  <p>{formatDateTime(event.startTime, event.timezone)}</p>
                  <p className="mt-1 text-xs text-slate-500">
                    Ends {formatDateTime(event.endTime, event.timezone)}
                  </p>
                </div>
              </div>
              <div className="flex items-start gap-2.5">
                <MapPin className="mt-0.5 h-4 w-4 shrink-0 text-blue-600" />
                <div>
                  <p className="font-semibold text-slate-900">{event.venueName}</p>
                  <p>{event.venueAddress}</p>
                  <p className="mt-1 text-xs text-slate-500">Venue capacity: {event.venueCapacity}</p>
                </div>
              </div>
            </div>

            <div className="mt-5 whitespace-pre-line text-sm leading-relaxed text-slate-700">
              {event.description}
            </div>

            {event.cancellationCutoffAt && (
              <div className="mt-6 flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
                <ShieldAlert className="h-4 w-4 text-slate-400" />
                <span>
                  Refundable cancellations allowed before{" "}
                  <strong>{formatDateTime(event.cancellationCutoffAt, event.timezone)}</strong>.
                </span>
              </div>
            )}
          </div>
        </div>

        <div>
          <CheckoutPanel event={event} isAuthenticated={!!user} onOrderUpdated={loadEvent} />
        </div>
      </div>
    </div>
  );
}
