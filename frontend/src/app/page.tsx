"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Calendar, MapPin, Search, Ticket, Users } from "lucide-react";
import { apiFetch } from "@/lib/api";
import { formatCents, formatDateTime } from "@/lib/format";
import { EventSummary, PageResponse } from "@/lib/types";

export default function HomePage() {
  const [events, setEvents] = useState<PageResponse<EventSummary> | null>(null);
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const buildQuery = useCallback(
    (targetPage: number) => {
      const params = new URLSearchParams();
      if (search.trim()) params.set("search", search.trim());
      if (category.trim()) params.set("category", category.trim());
      if (fromDate) params.set("from", new Date(fromDate).toISOString());
      if (toDate) params.set("to", new Date(toDate).toISOString());
      params.set("page", String(targetPage));
      params.set("size", "9");
      return params.toString();
    },
    [search, category, fromDate, toDate]
  );

  const loadEvents = useCallback(
    async (targetPage = page) => {
      setLoading(true);
      setError(null);
      try {
        const res = await apiFetch<PageResponse<EventSummary>>(`/events?${buildQuery(targetPage)}`);
        setEvents(res);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load events");
      } finally {
        setLoading(false);
      }
    },
    [buildQuery, page]
  );

  useEffect(() => {
    let active = true;
    apiFetch<PageResponse<EventSummary>>(`/events?${buildQuery(page)}`)
      .then((res) => {
        if (active) {
          setEvents(res);
          setError(null);
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load events");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [buildQuery, page]);

  const handleFilterSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    loadEvents(0);
  };

  return (
    <div className="space-y-8">
      <section className="rounded-2xl bg-gradient-to-r from-slate-900 via-blue-950 to-slate-900 px-6 py-10 text-white shadow-md sm:px-10">
        <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">
          Discover live events. Reserve without the race.
        </h1>
        <p className="mt-2 max-w-2xl text-sm text-slate-300 sm:text-base">
          Browse published events, lock seats with a 10-minute hold, download cryptographic QR tickets, and check in seamlessly at the door.
        </p>

        <form onSubmit={handleFilterSubmit} className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <div className="relative lg:col-span-2">
            <label htmlFor="event-search" className="sr-only">
              Search events
            </label>
            <Search className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-slate-400" />
            <input
              id="event-search"
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search title, venue, or description..."
              className="w-full rounded-lg border border-slate-700 bg-slate-800/90 py-2.5 pl-9 pr-3 text-sm text-white placeholder:text-slate-400 focus:border-blue-400 focus:outline-none"
            />
          </div>
          <div>
            <label htmlFor="event-category" className="sr-only">
              Category
            </label>
            <input
              id="event-category"
              type="text"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              placeholder="Category (e.g. Tech, Music)"
              className="w-full rounded-lg border border-slate-700 bg-slate-800/90 px-3 py-2.5 text-sm text-white placeholder:text-slate-400 focus:border-blue-400 focus:outline-none"
            />
          </div>
          <div>
            <label htmlFor="event-from" className="sr-only">
              Start date from
            </label>
            <input
              id="event-from"
              type="datetime-local"
              value={fromDate}
              onChange={(e) => setFromDate(e.target.value)}
              aria-label="Filter from date"
              className="w-full rounded-lg border border-slate-700 bg-slate-800/90 px-3 py-2.5 text-sm text-white focus:border-blue-400 focus:outline-none"
            />
          </div>
          <div className="flex gap-2">
            <input
              id="event-to"
              type="datetime-local"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
              aria-label="Filter to date"
              className="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-800/90 px-3 py-2.5 text-sm text-white focus:border-blue-400 focus:outline-none"
            />
            <button
              type="submit"
              className="shrink-0 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-500"
            >
              Filter
            </button>
          </div>
        </form>
      </section>

      {error && (
        <div role="alert" className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          {error}
          <button type="button" onClick={() => loadEvents()} className="ml-3 font-semibold underline">
            Retry
          </button>
        </div>
      )}

      {loading ? (
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3].map((n) => (
            <div key={n} className="h-56 animate-pulse rounded-xl border border-slate-200 bg-white p-6" />
          ))}
        </div>
      ) : events && events.items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-12 text-center">
          <Ticket className="mx-auto h-10 w-10 text-slate-400" />
          <h2 className="mt-3 text-lg font-semibold text-slate-900">No published events found</h2>
          <p className="mt-1 text-sm text-slate-500">
            Adjust your filters or register as an organizer to create and publish the first event.
          </p>
          <div className="mt-4 flex justify-center gap-3">
            <button
              type="button"
              onClick={() => {
                setSearch("");
                setCategory("");
                setFromDate("");
                setToDate("");
                setPage(0);
              }}
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Clear filters
            </button>
            <Link
              href="/organizer"
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700"
            >
              Create an Event
            </Link>
          </div>
        </div>
      ) : (
        <>
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {events?.items.map((ev) => {
              const soldOut = ev.totalAvailable <= 0;
              return (
                <Link
                  key={ev.id}
                  href={`/events/${ev.id}`}
                  className="group flex flex-col justify-between rounded-xl border border-slate-200 bg-white p-6 shadow-sm transition hover:border-blue-300 hover:shadow-md"
                >
                  <div>
                    <div className="flex items-center justify-between gap-2">
                      <span className="rounded-full bg-blue-50 px-2.5 py-0.5 text-xs font-semibold text-blue-700">
                        {ev.category}
                      </span>
                      {soldOut ? (
                        <span className="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-700">
                          Sold out
                        </span>
                      ) : ev.totalAvailable <= 5 ? (
                        <span className="rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-semibold text-amber-800">
                          Only {ev.totalAvailable} left
                        </span>
                      ) : (
                        <span className="text-xs font-medium text-slate-500">
                          {ev.totalAvailable} seats left
                        </span>
                      )}
                    </div>

                    <h2 className="mt-3 text-lg font-bold text-slate-900 group-hover:text-blue-700">
                      {ev.title}
                    </h2>

                    <div className="mt-3 space-y-1.5 text-sm text-slate-600">
                      <p className="flex items-center gap-2">
                        <Calendar className="h-4 w-4 shrink-0 text-slate-400" />
                        <span>{formatDateTime(ev.startTime, ev.timezone)}</span>
                      </p>
                      <p className="flex items-center gap-2">
                        <MapPin className="h-4 w-4 shrink-0 text-slate-400" />
                        <span>{ev.venueName}</span>
                      </p>
                    </div>
                  </div>

                  <div className="mt-5 flex items-center justify-between border-t border-slate-100 pt-3">
                    <span className="text-sm font-bold text-slate-900">
                      {ev.minPriceCents === 0 ? "Free" : `From ${formatCents(ev.minPriceCents)}`}
                    </span>
                    <div className="flex items-center gap-2">
                      {ev.waitingRoomEnabled && (
                        <span className="inline-flex items-center gap-1 text-xs text-indigo-600">
                          <Users className="h-3.5 w-3.5" />
                          Queue
                        </span>
                      )}
                      <span className="text-xs font-semibold text-blue-600">View tickets →</span>
                    </div>
                  </div>
                </Link>
              );
            })}
          </div>

          {events && events.totalPages > 1 && (
            <div className="flex items-center justify-between border-t border-slate-200 pt-4 text-sm">
              <span className="text-slate-600">
                Page {events.page + 1} of {events.totalPages} ({events.totalElements} events)
              </span>
              <div className="flex gap-2">
                <button
                  type="button"
                  disabled={page <= 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  className="rounded-lg border border-slate-300 px-3 py-1.5 font-medium text-slate-700 disabled:opacity-40"
                >
                  Previous
                </button>
                <button
                  type="button"
                  disabled={page + 1 >= events.totalPages}
                  onClick={() => setPage((p) => p + 1)}
                  className="rounded-lg border border-slate-300 px-3 py-1.5 font-medium text-slate-700 disabled:opacity-40"
                >
                  Next
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
