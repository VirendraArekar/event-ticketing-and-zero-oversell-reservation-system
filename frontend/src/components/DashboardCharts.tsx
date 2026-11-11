"use client";

import React from "react";
import { CheckInBucketPoint, SalesBucketPoint } from "@/lib/types";
import { formatCents } from "@/lib/format";

function formatBucketLabel(iso: string, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat("en-US", {
      hour: "2-digit",
      minute: "2-digit",
      timeZone,
    }).format(new Date(iso));
  } catch {
    return iso.slice(11, 16);
  }
}

export function CheckInCurveChart({
  data,
  timeZone,
}: {
  data: CheckInBucketPoint[];
  timeZone: string;
}) {
  if (!data.length) {
    return (
      <div className="flex h-48 items-center justify-center rounded-lg border border-dashed border-slate-200 text-sm text-slate-500">
        No check-ins recorded yet.
      </div>
    );
  }

  const maxCount = Math.max(1, ...data.map((d) => d.checkInCount));

  return (
    <div className="space-y-3" aria-label="Check-in curve per 15-minute interval">
      <div className="flex h-48 items-end gap-2 overflow-x-auto rounded-lg border border-slate-200 bg-slate-50 p-4">
        {data.map((point) => {
          const heightPct = Math.max(8, Math.round((point.checkInCount / maxCount) * 100));
          const label = formatBucketLabel(point.bucketStart, timeZone);
          return (
            <div key={point.bucketStart} className="flex min-w-[44px] flex-1 flex-col items-center gap-1">
              <span className="text-xs font-semibold text-slate-700">{point.checkInCount}</span>
              <div className="flex h-32 w-full items-end justify-center">
                <div
                  title={`${label}: ${point.checkInCount} check-ins`}
                  style={{ height: `${heightPct}%` }}
                  className="w-7 rounded-t bg-emerald-600 transition-all"
                />
              </div>
              <span className="text-[11px] text-slate-500">{label}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export function SalesOverTimeChart({
  data,
  timeZone,
}: {
  data: SalesBucketPoint[];
  timeZone: string;
}) {
  if (!data.length) {
    return (
      <div className="flex h-48 items-center justify-center rounded-lg border border-dashed border-slate-200 text-sm text-slate-500">
        No confirmed sales yet.
      </div>
    );
  }

  const maxSold = Math.max(1, ...data.map((d) => d.ticketsSold));

  return (
    <div className="space-y-3" aria-label="Ticket sales over time">
      <div className="flex h-48 items-end gap-2 overflow-x-auto rounded-lg border border-slate-200 bg-slate-50 p-4">
        {data.map((point) => {
          const heightPct = Math.max(8, Math.round((point.ticketsSold / maxSold) * 100));
          const label = formatBucketLabel(point.bucketStart, timeZone);
          return (
            <div key={point.bucketStart} className="flex min-w-[48px] flex-1 flex-col items-center gap-1">
              <span className="text-xs font-semibold text-slate-700">{point.ticketsSold}</span>
              <div className="flex h-32 w-full items-end justify-center">
                <div
                  title={`${label}: ${point.ticketsSold} sold (${formatCents(point.revenueCents)})`}
                  style={{ height: `${heightPct}%` }}
                  className="w-7 rounded-t bg-blue-600 transition-all"
                />
              </div>
              <span className="text-[11px] text-slate-500">{label}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

