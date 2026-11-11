"use client";

import React, { createContext, useCallback, useContext, useEffect, useState } from "react";
import { ApiError, apiFetch, clearStoredTokens, getStoredTokens, setStoredTokens } from "@/lib/api";
import { AuthResponse, Role, UserProfile } from "@/lib/types";

interface AuthContextValue {
  user: UserProfile | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<UserProfile>;
  register: (email: string, password: string, fullName: string, roles: Role[]) => Promise<UserProfile>;
  logout: () => Promise<void>;
  refreshProfile: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);

  const refreshProfile = useCallback(async () => {
    const { accessToken, refreshToken } = getStoredTokens();
    if (!accessToken && !refreshToken) {
      setUser(null);
      setLoading(false);
      return;
    }
    try {
      const profile = await apiFetch<UserProfile>("/auth/me");
      setUser(profile);
    } catch (err) {
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        clearStoredTokens();
        setUser(null);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;
    const { accessToken, refreshToken } = getStoredTokens();
    if (!accessToken && !refreshToken) {
      Promise.resolve().then(() => {
        if (active) {
          setUser(null);
          setLoading(false);
        }
      });
      return () => {
        active = false;
      };
    }
    apiFetch<UserProfile>("/auth/me")
      .then((profile) => {
        if (active) setUser(profile);
      })
      .catch((err) => {
        if (!active) return;
        if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
          clearStoredTokens();
          setUser(null);
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const login = useCallback(async (email: string, password: string): Promise<UserProfile> => {
    const res = await apiFetch<AuthResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    });
    setStoredTokens(res.accessToken, res.refreshToken);
    setUser(res.user);
    return res.user;
  }, []);

  const register = useCallback(
    async (email: string, password: string, fullName: string, roles: Role[]): Promise<UserProfile> => {
      const res = await apiFetch<AuthResponse>("/auth/register", {
        method: "POST",
        body: JSON.stringify({ email, password, fullName, roles }),
      });
      setStoredTokens(res.accessToken, res.refreshToken);
      setUser(res.user);
      return res.user;
    },
    []
  );

  const logout = useCallback(async () => {
    const { refreshToken } = getStoredTokens();
    try {
      await apiFetch<void>("/auth/logout", {
        method: "POST",
        body: JSON.stringify({ refreshToken }),
      });
    } catch {
      // Clear local session regardless
    } finally {
      clearStoredTokens();
      setUser(null);
    }
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, register, logout, refreshProfile }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}
