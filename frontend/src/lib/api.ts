import { AuthResponse } from "./types";

const API_BASE = (process.env.NEXT_PUBLIC_API_URL || "/api").replace(/\/$/, "");
const ACCESS_TOKEN_KEY = "gatekeeper_access_token";
const REFRESH_TOKEN_KEY = "gatekeeper_refresh_token";

export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: Record<string, string>;
  readonly traceId?: string;
  readonly retryAfterSeconds?: number;

  constructor(
    status: number,
    message: string,
    fieldErrors: Record<string, string> = {},
    traceId?: string,
    retryAfterSeconds?: number
  ) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors;
    this.traceId = traceId;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export function getStoredTokens(): { accessToken: string | null; refreshToken: string | null } {
  if (typeof window === "undefined") {
    return { accessToken: null, refreshToken: null };
  }
  return {
    accessToken: localStorage.getItem(ACCESS_TOKEN_KEY),
    refreshToken: localStorage.getItem(REFRESH_TOKEN_KEY),
  };
}

export function setStoredTokens(accessToken: string, refreshToken: string): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

export function clearStoredTokens(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

let refreshPromise: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) {
    return refreshPromise;
  }
  refreshPromise = (async () => {
    const { refreshToken } = getStoredTokens();
    if (!refreshToken) {
      return null;
    }
    try {
      const res = await fetch(`${API_BASE}/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });
      if (res.status === 401 || res.status === 403) {
        clearStoredTokens();
        return null;
      }
      if (!res.ok) {
        return null;
      }
      const data: AuthResponse = await res.json();
      setStoredTokens(data.accessToken, data.refreshToken);
      return data.accessToken;
    } catch {
      return null;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
}

async function rawRequest(
  path: string,
  options: RequestInit = {},
  retryOnAuthFailure = true
): Promise<Response> {
  const { accessToken } = getStoredTokens();
  const headers = new Headers(options.headers || {});
  if (accessToken && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  });

  if (
    res.status === 401 &&
    retryOnAuthFailure &&
    !path.startsWith("/auth/login") &&
    !path.startsWith("/auth/register") &&
    !path.startsWith("/auth/refresh")
  ) {
    const newAccess = await refreshAccessToken();
    if (newAccess) {
      const retryHeaders = new Headers(options.headers || {});
      retryHeaders.set("Authorization", `Bearer ${newAccess}`);
      if (options.body && !retryHeaders.has("Content-Type")) {
        retryHeaders.set("Content-Type", "application/json");
      }
      return fetch(`${API_BASE}${path}`, {
        ...options,
        headers: retryHeaders,
      });
    }
  }
  return res;
}

export async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await rawRequest(path, options, true);
  if (!res.ok) {
    let payload: { message?: string; fieldErrors?: Record<string, string>; traceId?: string } = {};
    try {
      payload = await res.json();
    } catch {
      // non-JSON response
    }
    const retryAfterHeader = res.headers.get("Retry-After");
    const retryAfter = retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : undefined;
    throw new ApiError(
      res.status,
      payload.message || `Request failed (${res.status})`,
      payload.fieldErrors || {},
      payload.traceId,
      Number.isFinite(retryAfter) ? retryAfter : undefined
    );
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return res.json() as Promise<T>;
}

export async function apiFetchBlob(path: string): Promise<Blob> {
  const res = await rawRequest(path, { method: "GET" }, true);
  if (!res.ok) {
    let message = `Download failed (${res.status})`;
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      // ignore
    }
    throw new ApiError(res.status, message);
  }
  return res.blob();
}

