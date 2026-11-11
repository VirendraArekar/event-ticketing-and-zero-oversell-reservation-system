"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Bell, Menu, QrCode, ShieldCheck, Ticket, X } from "lucide-react";
import { useAuth } from "@/context/AuthContext";
import { apiFetch } from "@/lib/api";
import { NotificationPage } from "@/lib/types";

export function Navbar() {
  const { user, logout } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  const [unreadCount, setUnreadCount] = useState(0);
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    if (!user) {
      return;
    }
    let active = true;
    const loadUnread = async () => {
      try {
        const res = await apiFetch<NotificationPage>("/notifications?page=0&size=1");
        if (active) setUnreadCount(res.unreadCount || 0);
      } catch {
        // ignore background poll failures
      }
    };
    const init = setTimeout(loadUnread, 0);
    const timer = setInterval(loadUnread, 15000);
    return () => {
      active = false;
      clearTimeout(init);
      clearInterval(timer);
    };
  }, [user, pathname]);

  const displayedUnread = user ? unreadCount : 0;

  const isOrganizer = user?.roles.includes("ORGANIZER");
  const isStaff = user?.roles.includes("STAFF") || (user?.staffEventIds?.length ?? 0) > 0;

  const navLinks = [
    { href: "/", label: "Events", show: true },
    { href: "/orders", label: "My Orders", show: !!user },
    { href: "/tickets", label: "My Tickets", show: !!user },
    { href: "/waitlist", label: "Waitlist", show: !!user },
    { href: "/organizer", label: "Organizer", show: !!isOrganizer },
    { href: "/scanner", label: "Scanner", show: !!isStaff },
  ].filter((item) => item.show);

  const handleLogout = async () => {
    await logout();
    setMobileOpen(false);
    router.push("/");
  };

  return (
    <header className="sticky top-0 z-40 border-b border-slate-200 bg-white/95 backdrop-blur">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3 sm:px-6">
        <div className="flex items-center gap-6">
          <Link href="/" className="flex items-center gap-2 text-lg font-bold text-slate-900">
            <ShieldCheck className="h-6 w-6 text-blue-600" />
            <span>GateKeeper</span>
          </Link>

          <nav aria-label="Main navigation" className="hidden items-center gap-1 md:flex">
            {navLinks.map((item) => {
              const active = pathname === item.href || (item.href !== "/" && pathname.startsWith(item.href));
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                    active ? "bg-blue-50 text-blue-700" : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                  }`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>
        </div>

        <div className="hidden items-center gap-3 md:flex">
          {user ? (
            <>
              <Link
                href="/notifications"
                aria-label="Notifications"
                className="relative rounded-md p-2 text-slate-600 hover:bg-slate-100 hover:text-slate-900"
              >
                <Bell className="h-5 w-5" />
                {displayedUnread > 0 && (
                  <span className="absolute -right-0.5 -top-0.5 inline-flex min-w-[18px] items-center justify-center rounded-full bg-blue-600 px-1 text-[11px] font-bold text-white">
                    {displayedUnread}
                  </span>
                )}
              </Link>
              <span className="text-sm font-medium text-slate-700">{user.fullName}</span>
              <button
                type="button"
                onClick={handleLogout}
                className="rounded-md border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Sign out
              </button>
            </>
          ) : (
            <>
              <Link
                href="/login"
                className="rounded-md px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100"
              >
                Sign in
              </Link>
              <Link
                href="/register"
                className="rounded-md bg-blue-600 px-3.5 py-1.5 text-sm font-semibold text-white hover:bg-blue-700"
              >
                Get started
              </Link>
            </>
          )}
        </div>

        <div className="flex items-center gap-2 md:hidden">
          {user && (
            <Link
              href="/notifications"
              aria-label="Notifications"
              className="relative rounded-md p-2 text-slate-600 hover:bg-slate-100"
            >
              <Bell className="h-5 w-5" />
              {unreadCount > 0 && (
                <span className="absolute -right-0.5 -top-0.5 inline-flex min-w-[18px] items-center justify-center rounded-full bg-blue-600 px-1 text-[11px] font-bold text-white">
                  {unreadCount}
                </span>
              )}
            </Link>
          )}
          <button
            type="button"
            aria-label="Toggle menu"
            aria-expanded={mobileOpen}
            onClick={() => setMobileOpen((prev) => !prev)}
            className="rounded-md p-2 text-slate-700 hover:bg-slate-100"
          >
            {mobileOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </button>
        </div>
      </div>

      {mobileOpen && (
        <div className="border-t border-slate-200 bg-white px-4 py-3 md:hidden">
          <div className="flex flex-col gap-1">
            {navLinks.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                onClick={() => setMobileOpen(false)}
                className="flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-100"
              >
                {item.href === "/scanner" && <QrCode className="h-4 w-4 text-blue-600" />}
                {item.href === "/tickets" && <Ticket className="h-4 w-4 text-blue-600" />}
                <span>{item.label}</span>
              </Link>
            ))}
            {user ? (
              <button
                type="button"
                onClick={handleLogout}
                className="mt-2 rounded-md border border-slate-300 px-3 py-2 text-left text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Sign out ({user.fullName})
              </button>
            ) : (
              <div className="mt-2 flex gap-2">
                <Link
                  href="/login"
                  onClick={() => setMobileOpen(false)}
                  className="flex-1 rounded-md border border-slate-300 px-3 py-2 text-center text-sm font-medium text-slate-700"
                >
                  Sign in
                </Link>
                <Link
                  href="/register"
                  onClick={() => setMobileOpen(false)}
                  className="flex-1 rounded-md bg-blue-600 px-3 py-2 text-center text-sm font-semibold text-white"
                >
                  Register
                </Link>
              </div>
            )}
          </div>
        </div>
      )}
    </header>
  );
}
