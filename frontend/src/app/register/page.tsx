"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import { ApiError } from "@/lib/api";
import { Role } from "@/lib/types";

export default function RegisterPage() {
  const { register } = useAuth();
  const router = useRouter();
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [asOrganizer, setAsOrganizer] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setFieldErrors({});
    const roles: Role[] = asOrganizer ? ["ATTENDEE", "ORGANIZER"] : ["ATTENDEE"];
    try {
      await register(email, password, fullName, roles);
      router.push(asOrganizer ? "/organizer" : "/");
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setFieldErrors(err.fieldErrors);
      } else {
        setError("Registration failed");
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
      <h1 className="text-2xl font-bold text-slate-900">Create your GateKeeper account</h1>
      <p className="mt-1 text-sm text-slate-600">Book tickets as an attendee or host events as an organizer.</p>

      {error && (
        <div role="alert" className="mt-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="mt-5 space-y-4">
        <div>
          <label htmlFor="reg-name" className="block text-sm font-medium text-slate-700">
            Full name
          </label>
          <input
            id="reg-name"
            type="text"
            required
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
          />
          {fieldErrors.fullName && <p className="mt-1 text-xs text-red-600">{fieldErrors.fullName}</p>}
        </div>

        <div>
          <label htmlFor="reg-email" className="block text-sm font-medium text-slate-700">
            Email address
          </label>
          <input
            id="reg-email"
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
          />
          {fieldErrors.email && <p className="mt-1 text-xs text-red-600">{fieldErrors.email}</p>}
        </div>

        <div>
          <label htmlFor="reg-password" className="block text-sm font-medium text-slate-700">
            Password (min 8 characters)
          </label>
          <input
            id="reg-password"
            type="password"
            required
            minLength={8}
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
          />
          {fieldErrors.password && <p className="mt-1 text-xs text-red-600">{fieldErrors.password}</p>}
        </div>

        <label className="flex items-start gap-2.5 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm">
          <input
            type="checkbox"
            checked={asOrganizer}
            onChange={(e) => setAsOrganizer(e.target.checked)}
            className="mt-0.5 h-4 w-4 rounded border-slate-300 text-blue-600"
          />
          <span>
            <strong className="block text-slate-900">Enable Organizer Studio</strong>
            <span className="text-xs text-slate-600">
              Create events, define ticket tiers, assign door staff, and view live analytics.
            </span>
          </span>
        </label>

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {submitting ? "Creating account..." : "Create Account"}
        </button>
      </form>

      <p className="mt-5 text-center text-sm text-slate-600">
        Already have an account?{" "}
        <Link href="/login" className="font-semibold text-blue-600 hover:underline">
          Sign in
        </Link>
      </p>
    </div>
  );
}

