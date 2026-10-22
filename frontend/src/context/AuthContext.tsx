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
  readonly accessToken: string | null;
  readonly user: UserResponse | null;
  authenticate(response: AuthResponse): void;
  updateCurrentUser(user: UserResponse): void;
  logout(): void;
  authorizedRequest<T>(request: (accessToken: string) => Promise<T>): Promise<T>;
  validateSession(signal?: AbortSignal): Promise<UserResponse>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const SESSION_STORAGE_KEY = "linkr.auth.session";

function isStoredUser(value: unknown): value is UserResponse {
  if (typeof value !== "object" || value === null) return false;
  const user = value as Partial<UserResponse>;
  return typeof user.id === "string"
    && typeof user.username === "string"
    && typeof user.email === "string"
    && typeof user.displayName === "string"
    && (typeof user.bio === "string" || user.bio === null)
    && (typeof user.avatarUrl === "string" || user.avatarUrl === null)
    && (user.role === "USER" || user.role === "ADMIN")
    && typeof user.createdAt === "string"
    && typeof user.discoverable === "boolean";
}

function usersAreEqual(left: UserResponse, right: UserResponse): boolean {
  return left.id === right.id
    && left.username === right.username
    && left.email === right.email
    && left.displayName === right.displayName
    && left.bio === right.bio
    && left.avatarUrl === right.avatarUrl
    && left.role === right.role
    && left.createdAt === right.createdAt
    && left.discoverable === right.discoverable;
}

function readStoredSession(): AuthSession | null {
  try {
    const raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return null;
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null) throw new Error("Invalid stored session");
    const candidate = value as Partial<AuthSession>;
    if (typeof candidate.accessToken !== "string"
      || typeof candidate.expiresAt !== "number"
      || !Number.isFinite(candidate.expiresAt)
      || !isStoredUser(candidate.user)
      || candidate.expiresAt <= Date.now()) {
      throw new Error("Invalid or expired stored session");
    }
    return {
      accessToken: candidate.accessToken,
      expiresAt: candidate.expiresAt,
      user: candidate.user,
    };
  } catch {
    try {
      window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
    } catch {
      // Storage bloccato dal browser: nessuna sessione persistita.
    }
    return null;
  }
}

function persistSession(session: AuthSession | null): void {
  try {
    if (session) window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
    else window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // Se sessionStorage non e' disponibile, l'app continua con una sessione solo in memoria.
  }
}

export function AuthProvider({ children }: { readonly children: ReactNode }) {
  // sessionStorage sopravvive ai refresh ma resta leggibile da JavaScript: una XSS potrebbe quindi sottrarre il JWT.
  // CSP, escaping React e dipendenze affidabili restano difese necessarie; non viene simulato alcun refresh token.
  const [session, setSession] = useState<AuthSession | null>(readStoredSession);

  const logout = useCallback(() => {
    persistSession(null);
    setSession(null);
  }, []);

  const authenticate = useCallback((response: AuthResponse) => {
    const expiresAt = Date.now() + Math.max(0, response.expiresIn) * 1_000;
    if (!response.accessToken || expiresAt <= Date.now()) {
      persistSession(null);
      setSession(null);
      return;
    }

    const nextSession = { accessToken: response.accessToken, expiresAt, user: response.user };
    persistSession(nextSession);
    setSession(nextSession);
  }, []);

  const updateCurrentUser = useCallback((user: UserResponse) => {
    setSession((current) => {
      if (!current || current.user.id !== user.id || usersAreEqual(current.user, user)) return current;
      const nextSession = { ...current, user };
      persistSession(nextSession);
      return nextSession;
    });
  }, []);

  const authorizedRequest = useCallback(async <T,>(request: (accessToken: string) => Promise<T>): Promise<T> => {
    if (!session) throw new Error("Authentication is required");
    if (session.expiresAt <= Date.now()) {
      persistSession(null);
      setSession(null);
      throw new Error("Authentication has expired");
    }

    try {
      return await request(session.accessToken);
    } catch (error) {
      if (isUnauthorized(error)) {
        setSession((current) => {
          if (current?.accessToken !== session.accessToken) return current;
          persistSession(null);
          return null;
        });
      }
      throw error;
    }
  }, [session]);

  const validateSession = useCallback(async (signal?: AbortSignal) => {
    const currentUser = await authorizedRequest((accessToken) => getCurrentUser(accessToken, signal));
    updateCurrentUser(currentUser);
    return currentUser;
  }, [authorizedRequest, updateCurrentUser]);

  useEffect(() => {
    if (!session) return undefined;

    const remaining = session.expiresAt - Date.now();
    if (remaining <= 0) {
      logout();
      return undefined;
    }

    const timeoutId = window.setTimeout(logout, remaining);
    return () => window.clearTimeout(timeoutId);
  }, [logout, session]);

  const value = useMemo<AuthContextValue>(() => ({
    status: session ? "authenticated" : "anonymous",
    accessToken: session?.accessToken ?? null,
    user: session?.user ?? null,
    authenticate,
    updateCurrentUser,
    logout,
    authorizedRequest,
    validateSession,
  }), [authenticate, authorizedRequest, logout, session, updateCurrentUser, validateSession]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
}
