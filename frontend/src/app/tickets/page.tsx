"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { Download, QrCode, Ticket } from "lucide-react";
import { apiFetch, apiFetchBlob } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { PageResponse, TicketResponse } from "@/lib/types";
import { useAuth } from "@/context/AuthContext";

function TicketQrImage({ ticketId, ticketCode }: { ticketId: string; ticketCode: string }) {
  const [src, setSrc] = useState<string | null>(null);

  useEffect(() => {
    let url: string | null = null;
    let active = true;
    apiFetchBlob(`/tickets/${ticketId}/qr`)
      .then((blob) => {
        if (!active) return;
        url = URL.createObjectURL(blob);
        setSrc(url);
      })
      .catch(() => {
        // fallback text shown below
      });
    return () => {
      active = false;
      if (url) URL.revokeObjectURL(url);
    };
  }, [ticketId]);

  if (!src) {
    return (
      <div className="flex h-40 w-40 items-center justify-center rounded-lg border border-slate-200 bg-slate-50 text-xs text-slate-400">
        <QrCode className="h-8 w-8" />
      </div>
    );
  }

  return (
    <Image
      src={src}
      alt={`QR code for ticket ${ticketCode}`}
      width={160}
      height={160}
      unoptimized
      className="h-40 w-40 rounded-lg border border-slate-200 bg-white p-1"
    />
  );
}

export default function TicketsPage() {
  const { user, loading: authLoading } = useAuth();
  const [tickets, setTickets] = useState<PageResponse<TicketResponse> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  useEffect(() => {
    if (authLoading || !user) return;
    let active = true;
    apiFetch<PageResponse<TicketResponse>>(`/tickets?page=${page}&size=10`)
      .then((res) => {
        if (active) {
          setTickets(res);
          setError(null);
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : "Failed to load tickets");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user, page]);

  const handleDownloadPdf = async (ticket: TicketResponse) => {
    try {
      const blob = await apiFetchBlob(`/tickets/${ticket.id}/pdf`);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `ticket-${ticket.ticketCode}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not download PDF ticket");
    }
  };

  if (!authLoading && !user) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-8 text-center">
        <p className="text-slate-700">Please sign in to access your QR tickets.</p>
        <Link href="/login" className="mt-3 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white">
          Sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-slate-900">My Tickets</h1>

      {error && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="grid gap-4 md:grid-cols-2">
          {[1, 2].map((n) => (
            <div key={n} className="h-52 animate-pulse rounded-xl bg-white p-6 shadow-sm" />
          ))}
        </div>
      ) : tickets && tickets.items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-12 text-center">
          <Ticket className="mx-auto h-10 w-10 text-slate-400" />
          <p className="mt-3 font-semibold text-slate-900">No tickets issued yet</p>
          <p className="mt-1 text-sm text-slate-500">Confirmed orders automatically issue QR tickets here.</p>
          <Link href="/" className="mt-4 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white">
            Browse Events
          </Link>
        </div>
      ) : (
        <>
          <div className="grid gap-6 md:grid-cols-2">
            {tickets?.items.map((ticket) => (
              <div
                key={ticket.id}
                className="flex flex-col justify-between gap-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm sm:flex-row"
              >
                <div className="space-y-2">
                  <div className="flex items-center gap-2">
                    <span
                      className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                        ticket.status === "VALID"
                          ? "bg-emerald-100 text-emerald-800"
                          : ticket.status === "CHECKED_IN"
                          ? "bg-blue-100 text-blue-800"
                          : "bg-red-100 text-red-800"
                      }`}
                    >
                      {ticket.status}
                    </span>
                    <span className="text-xs font-medium text-slate-500">{ticket.ticketTypeName}</span>
                  </div>

                  <h2 className="text-lg font-bold text-slate-900">{ticket.eventTitle}</h2>
                  <p className="text-sm text-slate-600">{ticket.venueName}</p>
                  <p className="text-xs text-slate-500">
                    {formatDateTime(ticket.eventStartTime, ticket.timezone)}
                  </p>

                  <div className="pt-1">
                    <p className="text-xs uppercase tracking-wide text-slate-400">Ticket Code</p>
                    <p className="font-mono text-sm font-bold text-slate-900">{ticket.ticketCode}</p>
                  </div>

                  {ticket.checkedInAt && (
                    <p className="text-xs font-medium text-blue-700">
                      Checked in {formatDateTime(ticket.checkedInAt, ticket.timezone)}
                    </p>
                  )}

                  <button
                    type="button"
                    onClick={() => handleDownloadPdf(ticket)}
                    className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
                  >
                    <Download className="h-3.5 w-3.5" />
                    Download PDF
                  </button>
                </div>

                <div className="flex shrink-0 flex-col items-center justify-center">
                  <TicketQrImage ticketId={ticket.id} ticketCode={ticket.ticketCode} />
                </div>
              </div>
            ))}
          </div>

          {tickets && tickets.totalPages > 1 && (
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
                Page {tickets.page + 1} of {tickets.totalPages}
              </span>
              <button
                type="button"
                disabled={page + 1 >= tickets.totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="rounded border border-slate-300 px-3 py-1.5 disabled:opacity-40"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
