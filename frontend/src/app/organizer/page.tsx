"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Plus, Settings2 } from "lucide-react";
import { apiFetch, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { EventDetail, EventSummary, PageResponse } from "@/lib/types";
import { useAuth } from "@/context/AuthContext";

function toLocalInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export default function OrganizerStudioPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();
  const [events, setEvents] = useState<PageResponse<EventSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("Technology");
  const [venueName, setVenueName] = useState("");
  const [venueAddress, setVenueAddress] = useState("");
  const [venueCapacity, setVenueCapacity] = useState(100);
  const [startTime, setStartTime] = useState(() =>
    toLocalInputValue(new Date(Date.now() + 2 * 60 * 60 * 1000))
  );
  const [endTime, setEndTime] = useState(() =>
    toLocalInputValue(new Date(Date.now() + 6 * 60 * 60 * 1000))
  );
  const [timezone, setTimezone] = useState("UTC");
  const [waitingRoomEnabled, setWaitingRoomEnabled] = useState(false);
  const [waitingRoomThreshold, setWaitingRoomThreshold] = useState(25);
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (authLoading || !user?.roles.includes("ORGANIZER")) return;
    let active = true;
    apiFetch<PageResponse<EventSummary>>("/organizer/events?page=0&size=20")
      .then((res) => {
        if (active) {
          setEvents(res);
          setError(null);
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load organizer events");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setFieldErrors({});
    try {
      const created = await apiFetch<EventDetail>("/organizer/events", {
        method: "POST",
        body: JSON.stringify({
          title,
          description,
          category,
          venueName,
          venueAddress,
          venueCapacity,
          startTime: new Date(startTime).toISOString(),
          endTime: new Date(endTime).toISOString(),
          timezone,
          waitingRoomEnabled,
          waitingRoomThreshold,
        }),
      });
      router.push(`/organizer/events/${created.id}`);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setFieldErrors(err.fieldErrors);
      } else {
        setError("Failed to create event");
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (!authLoading && (!user || !user.roles.includes("ORGANIZER"))) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-8 text-center">
        <p className="font-semibold text-slate-900">Organizer role required</p>
        <p className="mt-1 text-sm text-slate-500">
          Register an account with Organizer Studio enabled to create and manage events.
        </p>
        <Link href="/register" className="mt-4 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white">
          Register Organizer Account
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Organizer Studio</h1>
          <p className="text-sm text-slate-600">Create events, define ticket tiers, manage staff, and monitor live sales.</p>
        </div>
        <button
          type="button"
          onClick={() => setShowForm((prev) => !prev)}
          className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-700"
        >
          <Plus className="h-4 w-4" />
          {showForm ? "Close Form" : "Create Event"}
        </button>
      </div>

      {error && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {showForm && (
        <form onSubmit={handleCreate} className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="text-lg font-semibold text-slate-900">New Event (Draft)</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="ev-title" className="block text-sm font-medium text-slate-700">
                Title
              </label>
              <input
                id="ev-title"
                required
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
              {fieldErrors.title && <p className="mt-1 text-xs text-red-600">{fieldErrors.title}</p>}
            </div>

            <div>
              <label htmlFor="ev-category" className="block text-sm font-medium text-slate-700">
                Category
              </label>
              <input
                id="ev-category"
                required
                value={category}
                onChange={(e) => setCategory(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </div>

            <div className="sm:col-span-2">
              <label htmlFor="ev-desc" className="block text-sm font-medium text-slate-700">
                Description
              </label>
              <textarea
                id="ev-desc"
                required
                rows={3}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </div>

            <div>
              <label htmlFor="ev-venue" className="block text-sm font-medium text-slate-700">
                Venue Name
              </label>
              <input
                id="ev-venue"
                required
                value={venueName}
                onChange={(e) => setVenueName(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </div>

            <div>
              <label htmlFor="ev-address" className="block text-sm font-medium text-slate-700">
                Venue Address
              </label>
              <input
                id="ev-address"
                required
                value={venueAddress}
                onChange={(e) => setVenueAddress(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </div>

            <div>
              <label htmlFor="ev-cap" className="block text-sm font-medium text-slate-700">
                Venue Capacity
              </label>
              <input
                id="ev-cap"
                type="number"
                min={1}
                required
                value={venueCapacity}
                onChange={(e) => setVenueCapacity(Number.parseInt(e.target.value || "1", 10))}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </div>

            <div>
              <label htmlFor="ev-tz" className="block text-sm font-medium text-slate-700">
                Time Zone (IANA)
              </label>
              <select
                id="ev-tz"
                value={timezone}
                onChange={(e) => setTimezone(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
              >
                <option value="UTC">UTC</option>
                <option value="America/New_York">America/New_York</option>
                <option value="America/Los_Angeles">America/Los_Angeles</option>
                <option value="Europe/London">Europe/London</option>
                <option value="Asia/Kolkata">Asia/Kolkata</option>
              </select>
            </div>

            <div>
              <label htmlFor="ev-start" className="block text-sm font-medium text-slate-700">
                Start Time
              </label>
              <input
                id="ev-start"
                type="datetime-local"
                required
                value={startTime}
                onChange={(e) => setStartTime(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </div>

            <div>
              <label htmlFor="ev-end" className="block text-sm font-medium text-slate-700">
                End Time
              </label>
              <input
                id="ev-end"
                type="datetime-local"
                required
                value={endTime}
                onChange={(e) => setEndTime(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-4 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm">
            <label className="flex items-center gap-2 font-medium text-slate-800">
              <input
                type="checkbox"
                checked={waitingRoomEnabled}
                onChange={(e) => setWaitingRoomEnabled(e.target.checked)}
                className="h-4 w-4 rounded border-slate-300 text-blue-600"
              />
              Enable Virtual Waiting Room
            </label>
            {waitingRoomEnabled && (
              <div className="flex items-center gap-2">
                <label htmlFor="ev-wr-thresh" className="text-xs text-slate-600">
                  Max active slots:
                </label>
                <input
                  id="ev-wr-thresh"
                  type="number"
                  min={1}
                  value={waitingRoomThreshold}
                  onChange={(e) => setWaitingRoomThreshold(Number.parseInt(e.target.value || "25", 10))}
                  className="w-20 rounded border border-slate-300 px-2 py-1 text-sm"
                />
              </div>
            )}
          </div>

          <button
            type="submit"
            disabled={submitting}
            className="rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {submitting ? "Creating..." : "Create Draft Event"}
          </button>
        </form>
      )}

      {loading ? (
        <div className="h-44 animate-pulse rounded-xl bg-white p-6" />
      ) : events && events.items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-12 text-center">
          <p className="font-semibold text-slate-900">You haven&apos;t created any events yet</p>
          <p className="mt-1 text-sm text-slate-500">Click &quot;Create Event&quot; above to launch your first event.</p>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {events?.items.map((ev) => (
            <div key={ev.id} className="flex flex-col justify-between rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
              <div>
                <div className="flex items-center justify-between">
                  <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-700">
                    {ev.status}
                  </span>
                  <span className="text-xs text-slate-500">Cap: {ev.venueCapacity}</span>
                </div>
                <h3 className="mt-3 text-lg font-bold text-slate-900">{ev.title}</h3>
                <p className="mt-1 text-xs text-slate-500">{formatDateTime(ev.startTime, ev.timezone)}</p>
                <p className="mt-1 text-sm text-slate-600">{ev.venueName}</p>
              </div>
              <Link
                href={`/organizer/events/${ev.id}`}
                className="mt-4 inline-flex items-center justify-center gap-1.5 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800"
              >
                <Settings2 className="h-4 w-4" />
                Manage & Dashboard
              </Link>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
