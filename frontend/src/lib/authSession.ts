/**
 * Sessione di accesso nel browser: lettura, scrittura e scadenza. Nessuna dipendenza dal DOM o da React: lo storage arriva come parametro,
 * quindi si prova con `node --test`.
 *
 * Il JWT (2 ore di default, nessun refresh token) sta in `localStorage`: sopravvive alla chiusura della scheda e della web app aggiunta alla
 * Home, che con `sessionStorage` finivano ogni volta nel login. Il token resta leggibile da JavaScript come prima (una XSS potrebbe
 * sottrarlo: CSP, escaping React e dipendenze affidabili restano le difese) ma ha la stessa scadenza di prima e il logout lo cancella.
 */
import type { UserResponse } from "../types/auth";

export interface AuthSession {
  readonly accessToken: string;
  readonly expiresAt: number;
  readonly user: UserResponse;
}

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export const SESSION_STORAGE_KEY = "linkr.auth.session";

/** `setTimeout` accetta al massimo 2^31-1 ms: oltre, scatta subito. Con scadenze lunghe (giorni) il timer si riarma a tappe. */
export const MAX_TIMER_MS = 2_147_483_647;

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

/** Una sessione valida e non scaduta, oppure `null` (testo assente, non JSON, forma sbagliata, scaduta). */
export function parseStoredSession(raw: string | null, now: number): AuthSession | null {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null) return null;
    const candidate = value as Partial<AuthSession>;
    if (typeof candidate.accessToken !== "string" || candidate.accessToken.length === 0
      || typeof candidate.expiresAt !== "number" || !Number.isFinite(candidate.expiresAt)
      || !isStoredUser(candidate.user)
      || candidate.expiresAt <= now) {
      return null;
    }
    return { accessToken: candidate.accessToken, expiresAt: candidate.expiresAt, user: candidate.user };
  } catch {
    return null;
  }
}

function safely<T>(action: () => T, fallback: T): T {
  try {
    return action();
  } catch {
    // Storage bloccato dal browser (navigazione privata, impostazioni): la sessione resta solo in memoria.
    return fallback;
  }
}

/**
 * Legge la sessione salvata. Chi era già connesso con la vecchia versione (token in `sessionStorage`) non viene disconnesso dall'aggiornamento:
 * la sessione valida si sposta in `localStorage`. Qualunque voce non valida o scaduta viene cancellata.
 */
export function loadSession(persistent: StorageLike | null, legacy: StorageLike | null, now: number): AuthSession | null {
  const stored = persistent ? safely(() => persistent.getItem(SESSION_STORAGE_KEY), null) : null;
  const current = parseStoredSession(stored, now);
  if (current) {
    if (legacy) safely(() => legacy.removeItem(SESSION_STORAGE_KEY), undefined);
    return current;
  }
  if (stored !== null && persistent) safely(() => persistent.removeItem(SESSION_STORAGE_KEY), undefined);

  const old = legacy ? safely(() => legacy.getItem(SESSION_STORAGE_KEY), null) : null;
  const migrated = parseStoredSession(old, now);
  if (legacy && old !== null) safely(() => legacy.removeItem(SESSION_STORAGE_KEY), undefined);
  if (migrated && persistent) safely(() => persistent.setItem(SESSION_STORAGE_KEY, JSON.stringify(migrated)), undefined);
  return migrated;
}

export function saveSession(persistent: StorageLike | null, legacy: StorageLike | null, session: AuthSession | null): void {
  if (legacy) safely(() => legacy.removeItem(SESSION_STORAGE_KEY), undefined);
  if (!persistent) return;
  if (session) safely(() => persistent.setItem(SESSION_STORAGE_KEY, JSON.stringify(session)), undefined);
  else safely(() => persistent.removeItem(SESSION_STORAGE_KEY), undefined);
}

export function isExpired(session: Pick<AuthSession, "expiresAt">, now: number): boolean {
  return session.expiresAt <= now;
}

/** Tra quanto armare il timer di scadenza: mai oltre il limite di `setTimeout`; allo scattare si ricontrolla con `isExpired`. */
export function nextExpiryCheckDelay(session: Pick<AuthSession, "expiresAt">, now: number): number {
  return Math.min(Math.max(0, session.expiresAt - now), MAX_TIMER_MS);
}
