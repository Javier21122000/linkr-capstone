interface ReturnLocation {
  readonly pathname?: unknown;
  readonly search?: unknown;
  readonly hash?: unknown;
}

interface ReturnState {
  readonly from?: ReturnLocation;
}

export function getSafeReturnPath(state: unknown): string {
  if (typeof state !== "object" || state === null || !("from" in state)) return "/";

  const { from } = state as ReturnState;
  if (!from || typeof from.pathname !== "string") return "/";

  const pathname = from.pathname;
  if (!pathname.startsWith("/") || pathname.startsWith("//") || pathname.includes("\\")) return "/";
  if (pathname === "/login" || pathname === "/register") return "/";

  const search = typeof from.search === "string" && from.search.startsWith("?") ? from.search : "";
  const hash = typeof from.hash === "string" && from.hash.startsWith("#") ? from.hash : "";
  return `${pathname}${search}${hash}`;
}

const EVENT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Richiesta "Visualizza sulla mappa": porta a Discover e lì seleziona e inquadra l'evento. Viaggia nello stato della navigazione, non nell'URL. */
export interface MapFocusRequest {
  readonly eventId: string;
  /** Distingue due richieste per lo stesso evento (e permette a Discover di sapere quale ha già gestito). */
  readonly requestedAt: number;
}

export function createMapFocusState(eventId: string, now: number = Date.now()): { readonly mapFocus: MapFocusRequest } {
  return { mapFocus: { eventId, requestedAt: now } };
}

export function readMapFocusRequest(state: unknown): MapFocusRequest | null {
  if (typeof state !== "object" || state === null || !("mapFocus" in state)) return null;
  const { mapFocus } = state as { readonly mapFocus: unknown };
  if (typeof mapFocus !== "object" || mapFocus === null) return null;
  const { eventId, requestedAt } = mapFocus as { readonly eventId?: unknown; readonly requestedAt?: unknown };
  if (typeof eventId !== "string" || !EVENT_ID.test(eventId) || typeof requestedAt !== "number" || !Number.isFinite(requestedAt)) return null;
  return { eventId, requestedAt };
}
