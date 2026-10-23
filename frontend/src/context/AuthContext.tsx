import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

import { getCurrentUser, isUnauthorized } from "../lib/auth";
import { isExpired, loadSession, nextExpiryCheckDelay, parseStoredSession, saveSession, SESSION_STORAGE_KEY } from "../lib/authSession";
import type { AuthSession, StorageLike } from "../lib/authSession";
import type { AuthResponse, UserResponse } from "../types/auth";

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

/** `localStorage` tiene la sessione tra una apertura e l'altra; `sessionStorage` è solo la sede della vecchia versione (migrazione). */
function browserStorage(kind: "localStorage" | "sessionStorage"): StorageLike | null {
  try {
    return window[kind];
  } catch {
    return null;
  }
}

function readStoredSession(): AuthSession | null {
  return loadSession(browserStorage("localStorage"), browserStorage("sessionStorage"), Date.now());
}

function persistSession(session: AuthSession | null): void {
  saveSession(browserStorage("localStorage"), browserStorage("sessionStorage"), session);
}

export function AuthProvider({ children }: { readonly children: ReactNode }) {
  // Il JWT sta in localStorage (vedi lib/authSession.ts): sopravvive a refresh, chiusura della scheda e della web app dalla Home, fino alla sua
  // scadenza. Resta leggibile da JavaScript, quindi CSP, escaping React e dipendenze affidabili restano difese necessarie; non c'è refresh token.
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

  // Scadenza: un timer (a tappe, perché setTimeout non regge scadenze oltre ~24 giorni) e un controllo quando l'app torna in primo piano:
  // i timer di una web app sospesa in background non scattano, e al ritorno non deve restare una sessione scaduta mostrata come attiva.
  useEffect(() => {
    if (!session) return undefined;

    let timeoutId: number | undefined;
    const check = () => {
      if (isExpired(session, Date.now())) {
        logout();
        return;
      }
      timeoutId = window.setTimeout(check, nextExpiryCheckDelay(session, Date.now()));
    };
    const handleResume = () => {
      if (document.visibilityState === "visible") check();
    };

    check();
    document.addEventListener("visibilitychange", handleResume);
    window.addEventListener("pageshow", handleResume);
    return () => {
      window.clearTimeout(timeoutId);
      document.removeEventListener("visibilitychange", handleResume);
      window.removeEventListener("pageshow", handleResume);
    };
  }, [logout, session]);

  // Più schede (o la web app e Safari che condividono lo storage): un accesso o un logout fatto altrove si riflette qui. L'evento `storage`
  // arriva solo dalle altre schede, mai da quella che ha scritto.
  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key !== null && event.key !== SESSION_STORAGE_KEY) return;
      const next = parseStoredSession(event.key === null ? null : event.newValue, Date.now());
      setSession((current) => (next?.accessToken === current?.accessToken ? current : next));
    };
    window.addEventListener("storage", handleStorage);
    return () => window.removeEventListener("storage", handleStorage);
  }, []);

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
