"use client";

import React, { useEffect, useRef, useState } from "react";
import { BrowserQRCodeReader, IScannerControls } from "@zxing/browser";
import { Camera, CameraOff, CheckCircle2, Keyboard, XCircle } from "lucide-react";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { CheckInLog, CheckInResponse, EventSummary, PageResponse } from "@/lib/types";

interface ScannerPanelProps {
  assignedEvents: EventSummary[];
}

export function ScannerPanel({ assignedEvents }: ScannerPanelProps) {
  const [selectedEventId, setSelectedEventId] = useState<string>(assignedEvents[0]?.id || "");
  const effectiveEventId = selectedEventId || assignedEvents[0]?.id || "";
  const [manualInput, setManualInput] = useState("");
  const [cameraActive, setCameraActive] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [lastResult, setLastResult] = useState<{
    ok: boolean;
    message: string;
    details?: CheckInResponse;
  } | null>(null);
  const [recentLogs, setRecentLogs] = useState<CheckInLog[]>([]);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const controlsRef = useRef<IScannerControls | null>(null);
  const cooldownRef = useRef<string | null>(null);

  const loadRecentLogs = async (eventId: string) => {
    if (!eventId) return;
    try {
      const res = await apiFetch<PageResponse<CheckInLog>>(`/events/${eventId}/checkin/recent?page=0&size=15`);
      setRecentLogs(res.items);
    } catch {
      // ignore if unauthorized
    }
  };

  useEffect(() => {
    if (!effectiveEventId) return;
    let active = true;
    apiFetch<PageResponse<CheckInLog>>(`/events/${effectiveEventId}/checkin/recent?page=0&size=15`)
      .then((res) => {
        if (active) setRecentLogs(res.items);
      })
      .catch(() => {
        // ignore if unauthorized
      });
    return () => {
      active = false;
    };
  }, [effectiveEventId]);

  const submitScanPayload = async (rawValue: string) => {
    const trimmed = rawValue.trim();
    if (!trimmed || !effectiveEventId || scanning) return;
    setScanning(true);

    const payload = trimmed.startsWith("gk1.")
      ? { qrToken: trimmed }
      : { ticketCode: trimmed };

    try {
      const res = await apiFetch<CheckInResponse>(`/events/${effectiveEventId}/checkin`, {
        method: "POST",
        body: JSON.stringify(payload),
      });
      setLastResult({
        ok: true,
        message: `Checked in ${res.attendeeName || "Guest"} (${res.ticketTypeName})`,
        details: res,
      });
      setManualInput("");
      await loadRecentLogs(effectiveEventId);
    } catch (err) {
      setLastResult({
        ok: false,
        message: err instanceof Error ? err.message : "Check-in rejected",
      });
      await loadRecentLogs(effectiveEventId);
    } finally {
      setScanning(false);
    }
  };

  const stopCamera = () => {
    controlsRef.current?.stop();
    controlsRef.current = null;
    setCameraActive(false);
  };

  const startCamera = async () => {
    if (!videoRef.current || !effectiveEventId) return;
    setCameraError(null);
    try {
      const reader = new BrowserQRCodeReader();
      setCameraActive(true);
      const controls = await reader.decodeFromConstraints(
        { video: { facingMode: "environment" } },
        videoRef.current,
        (result) => {
          if (result) {
            const text = result.getText();
            if (cooldownRef.current === text) return;
            cooldownRef.current = text;
            setTimeout(() => {
              if (cooldownRef.current === text) cooldownRef.current = null;
            }, 2500);
            submitScanPayload(text);
          }
        }
      );
      controlsRef.current = controls;
    } catch (err) {
      setCameraActive(false);
      setCameraError(
        err instanceof Error ? err.message : "Camera access unavailable; use manual code entry below."
      );
    }
  };

  useEffect(() => {
    return () => {
      controlsRef.current?.stop();
    };
  }, []);

  if (assignedEvents.length === 0) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-8 text-center">
        <p className="text-base font-medium text-slate-800">No door check-in assignments found</p>
        <p className="mt-1 text-sm text-slate-500">
          Ask an event organizer to assign your account email as check-in staff for an event.
        </p>
      </div>
    );
  }

  const selectedEvent = assignedEvents.find((e) => e.id === effectiveEventId);

  return (
    <div className="space-y-6">
      <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <label htmlFor="scanner-event" className="block text-sm font-medium text-slate-700">
          Active Event Door
        </label>
        <select
          id="scanner-event"
          value={effectiveEventId}
          onChange={(e) => {
            stopCamera();
            setLastResult(null);
            setSelectedEventId(e.target.value);
          }}
          className="mt-1.5 w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm font-medium text-slate-900"
        >
          {assignedEvents.map((ev) => (
            <option key={ev.id} value={ev.id}>
              {ev.title} — {ev.venueName}
            </option>
          ))}
        </select>

        <div className="mt-4 flex flex-wrap items-center gap-3">
          {!cameraActive ? (
            <button
              type="button"
              onClick={startCamera}
              className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-700"
            >
              <Camera className="h-4 w-4" />
              Start Camera QR Scan
            </button>
          ) : (
            <button
              type="button"
              onClick={stopCamera}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50"
            >
              <CameraOff className="h-4 w-4" />
              Stop Camera
            </button>
          )}
        </div>

        {cameraError && (
          <p role="alert" className="mt-3 text-sm text-amber-700">
            {cameraError}
          </p>
        )}

        <div className={cameraActive ? "mt-4 overflow-hidden rounded-xl border border-slate-300 bg-black" : "hidden"}>
          <video ref={videoRef} className="aspect-video w-full object-cover" muted playsInline />
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            submitScanPayload(manualInput);
          }}
          className="mt-5 space-y-2 border-t border-slate-200 pt-4"
        >
          <label htmlFor="manual-ticket-input" className="flex items-center gap-2 text-sm font-medium text-slate-700">
            <Keyboard className="h-4 w-4 text-slate-500" />
            Manual Fallback (Ticket Code or QR Token)
          </label>
          <div className="flex flex-col gap-2 sm:flex-row">
            <input
              id="manual-ticket-input"
              type="text"
              value={manualInput}
              onChange={(e) => setManualInput(e.target.value)}
              placeholder="e.g. GK-9F2A... or gk1.ticketId..."
              className="flex-1 rounded-lg border border-slate-300 px-3 py-2.5 font-mono text-sm"
            />
            <button
              type="submit"
              disabled={scanning || !manualInput.trim()}
              className="rounded-lg bg-slate-900 px-5 py-2.5 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
            >
              {scanning ? "Verifying..." : "Verify & Check In"}
            </button>
          </div>
        </form>
      </div>

      {lastResult && (
        <div
          role="status"
          aria-live="assertive"
          className={`rounded-xl border-2 p-5 shadow-sm ${
            lastResult.ok
              ? "border-emerald-500 bg-emerald-50 text-emerald-950"
              : "border-red-500 bg-red-50 text-red-950"
          }`}
        >
          <div className="flex items-start gap-3">
            {lastResult.ok ? (
              <CheckCircle2 className="mt-0.5 h-7 w-7 shrink-0 text-emerald-600" />
            ) : (
              <XCircle className="mt-0.5 h-7 w-7 shrink-0 text-red-600" />
            )}
            <div>
              <p className="text-lg font-bold">{lastResult.ok ? "ADMITTED — CHECKED IN" : "ENTRY REJECTED"}</p>
              <p className="mt-1 text-sm font-medium">{lastResult.message}</p>
              {lastResult.details && (
                <p className="mt-1 font-mono text-xs text-emerald-800">
                  Code: {lastResult.details.ticketCode} • Checked in at{" "}
                  {formatDateTime(lastResult.details.checkedInAt, selectedEvent?.timezone)}
                </p>
              )}
            </div>
          </div>
        </div>
      )}

      <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <h3 className="text-sm font-semibold text-slate-900">Recent Gate Activity</h3>
        {recentLogs.length === 0 ? (
          <p className="mt-2 text-sm text-slate-500">No scans recorded yet for this event.</p>
        ) : (
          <ul className="mt-3 divide-y divide-slate-200 text-sm">
            {recentLogs.map((log) => (
              <li key={log.id} className="flex flex-col gap-1 py-2 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <span
                    className={`mr-2 inline-block rounded px-2 py-0.5 text-xs font-semibold ${
                      log.outcome === "SUCCESS"
                        ? "bg-emerald-100 text-emerald-800"
                        : "bg-red-100 text-red-800"
                    }`}
                  >
                    {log.outcome}
                  </span>
                  <span className="text-slate-700">{log.notes || log.ticketId}</span>
                </div>
                <span className="text-xs text-slate-500">
                  {formatDateTime(log.scannedAt, selectedEvent?.timezone)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
