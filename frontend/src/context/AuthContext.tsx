import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

import { getCurrentUser, isUnauthorized } from "../lib/auth";
import type { AuthResponse, UserResponse } from "../types/auth";

interface AuthSession {
  readonly accessToken: string;
  readonly expiresAt: number;
  readonly user: UserResponse;
}

interface AuthContextValue {
  readonly status: "anonymous" | "authenticated";
  readonly user: UserResponse | null;
  authenticate(response: AuthResponse): void;
  logout(): void;
  authorizedRequest<T>(request: (accessToken: string) => Promise<T>): Promise<T>;
  validateSession(signal?: AbortSignal): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { readonly children: ReactNode }) {
  const [session, setSession] = useState<AuthSession | null>(null);

  const logout = useCallback(() => setSession(null), []);

  const authenticate = useCallback((response: AuthResponse) => {
    const expiresAt = Date.now() + Math.max(0, response.expiresIn) * 1_000;
    if (!response.accessToken || expiresAt <= Date.now()) {
      setSession(null);
      return;
    }

    setSession({ accessToken: response.accessToken, expiresAt, user: response.user });
  }, []);

  const authorizedRequest = useCallback(async <T,>(request: (accessToken: string) => Promise<T>): Promise<T> => {
    if (!session) throw new Error("Authentication is required");
    if (session.expiresAt <= Date.now()) {
      setSession(null);
      throw new Error("Authentication has expired");
    }

    try {
      return await request(session.accessToken);
    } catch (error) {
      if (isUnauthorized(error)) {
        setSession((current) => current?.accessToken === session.accessToken ? null : current);
      }
      throw error;
    }
  }, [session]);

  const validateSession = useCallback(async (signal?: AbortSignal) => {
    await authorizedRequest((accessToken) => getCurrentUser(accessToken, signal));
  }, [authorizedRequest]);

  useEffect(() => {
    if (!session) return undefined;

    const remaining = session.expiresAt - Date.now();
    if (remaining <= 0) {
      setSession(null);
      return undefined;
    }

    const timeoutId = window.setTimeout(logout, remaining);
    return () => window.clearTimeout(timeoutId);
  }, [logout, session]);

  const value = useMemo<AuthContextValue>(() => ({
    status: session ? "authenticated" : "anonymous",
    user: session?.user ?? null,
    authenticate,
    logout,
    authorizedRequest,
    validateSession,
  }), [authenticate, authorizedRequest, logout, session, validateSession]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
}
