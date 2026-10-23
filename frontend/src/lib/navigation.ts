interface ReturnLocation {
  readonly pathname?: unknown;
  readonly search?: unknown;
  readonly hash?: unknown;
}

interface ReturnState {
  readonly from?: ReturnLocation;
}

/** Un percorso interno all'app, mai un indirizzo esterno: parte da una sola "/", senza "\\", spazi o caratteri di controllo. */
const UNSAFE_PATH_CHARS = /[\u0000-\u0020\u007f\\]/;

/**
 * Dove tornare dopo accesso o registrazione, se chi ha mandato all'accesso lo ha detto (`state.from`, la posizione che il router ha già).
 * `null` = nessun ritorno valido. Un percorso esterno o malformato non è mai un ritorno valido: `//host`, `http://host`, `/\host`, `/login`.
 */
export function readReturnPath(state: unknown): string | null {
  if (typeof state !== "object" || state === null || !("from" in state)) return null;

  const { from } = state as ReturnState;
  if (!from || typeof from.pathname !== "string") return null;

  const pathname = from.pathname;
  if (!pathname.startsWith("/") || pathname.startsWith("//") || UNSAFE_PATH_CHARS.test(pathname)) return null;
  if (pathname === "/login" || pathname === "/register") return null;

  const search = typeof from.search === "string" && from.search.startsWith("?") && !UNSAFE_PATH_CHARS.test(from.search) ? from.search : "";
  const hash = typeof from.hash === "string" && from.hash.startsWith("#") && !UNSAFE_PATH_CHARS.test(from.hash) ? from.hash : "";
  return `${pathname}${search}${hash}`;
}

export function getSafeReturnPath(state: unknown): string {
  return readReturnPath(state) ?? "/";
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

/**
 * Ritorno contestuale: da dove si è arrivati a un evento quando non è Discover (il predefinito). Nello stato di navigazione viaggia solo una
 * chiave di un elenco chiuso, mai un percorso: nessun indirizzo arbitrario può diventare un link "Torna a…".
 */
export type BackContext = "admin-events";

export interface BackTarget {
  readonly to: string;
  readonly label: string;
}

const BACK_TARGETS: Readonly<Record<BackContext, BackTarget>> = {
  "admin-events": { to: "/admin?section=events", label: "Admin · Eventi" },
};

export interface BackRequest {
  readonly context: BackContext;
  readonly target: BackTarget;
  /** Si è già passati dal dettaglio (es. dal dettaglio a Modifica): il ritorno va al dettaglio, che porta poi il contesto. */
  readonly viaDetail: boolean;
}

export function createBackState(context: BackContext, viaDetail = false): { readonly backTo: BackContext; readonly viaDetail?: true } {
  return viaDetail ? { backTo: context, viaDetail: true } : { backTo: context };
}

export function readBackRequest(state: unknown): BackRequest | null {
  if (typeof state !== "object" || state === null || !("backTo" in state)) return null;
  const { backTo, viaDetail } = state as { readonly backTo: unknown; readonly viaDetail?: unknown };
  if (typeof backTo !== "string" || !Object.hasOwn(BACK_TARGETS, backTo)) return null;
  const context = backTo as BackContext;
  return { context, target: BACK_TARGETS[context], viaDetail: viaDetail === true };
}
