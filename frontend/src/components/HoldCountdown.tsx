"use client";

import React, { useEffect, useRef, useState } from "react";
import { Clock, AlertTriangle } from "lucide-react";
import { formatRemainingSeconds } from "@/lib/format";

interface HoldCountdownProps {
  expiresAt: string;
  onExpire?: () => void;
}

export function HoldCountdown({ expiresAt, onExpire }: HoldCountdownProps) {
  const computeRemaining = () =>
    Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));

  const [secondsLeft, setSecondsLeft] = useState<number>(computeRemaining);
  const expiredNotifiedRef = useRef(false);

  useEffect(() => {
    expiredNotifiedRef.current = false;
    const tick = () => {
      const next = Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
      setSecondsLeft(next);
      if (next === 0 && !expiredNotifiedRef.current) {
        expiredNotifiedRef.current = true;
        onExpire?.();
      }
    };
    const init = setTimeout(tick, 0);
    const timer = setInterval(tick, 1000);

    return () => {
      clearTimeout(init);
      clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expiresAt]);

  if (secondsLeft <= 0) {
    return (
      <div
        role="status"
        aria-live="assertive"
        className="flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm font-medium text-red-700"
      >
        <AlertTriangle className="h-4 w-4 shrink-0" />
        <span>Your hold expired</span>
      </div>
    );
  }

  const urgent = secondsLeft <= 120;

  return (
    <div
      role="timer"
      aria-live="polite"
      className={`flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium ${
        urgent
          ? "border-amber-300 bg-amber-50 text-amber-800"
          : "border-blue-200 bg-blue-50 text-blue-800"
      }`}
    >
      <Clock className="h-4 w-4 shrink-0" />
      <span>
        Seats held — confirm within{" "}
        <strong className="font-mono">{formatRemainingSeconds(secondsLeft)}</strong>
      </span>
    </div>
  );
}
