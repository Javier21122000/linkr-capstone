/**
 * Salute della mappa di Discover: che cosa ha fallito, se vale la pena riprovare e quanto aspettare.
 *
 * Il motivo di questo modulo è un fatto verificato: quando i tassello vettoriali falliscono (rete, 429, 503, 403) MapLibre emette `error`
 * ma poi dichiara comunque `load` e `idle`, e non ritenta da solo i tasselli già falliti. Il risultato è una mappa "pronta" con marker e
 * controlli ma senza strade né edifici. Qui si decide cosa è un errore da gestire (e cosa no) e con quale regola ritentare.
 *
 * Modulo senza dipendenze (testabile con `node --test`).
 */
export type MapFailureKind =
  /** Lo stile non si scarica: la mappa non può partire. */
  | "style"
  /** Tilejson o tasselli della cartografia: senza di loro lo sfondo resta vuoto. */
  | "basemap"
  /** Sprite o glifi: mancano icone o etichette, la geometria c'è. */
  | "cosmetic"
  /** Errore senza URL (es. WebGL non disponibile). */
  | "unknown"
  /** Non è un problema (es. un tassello senza dati). */
  | "ignore";

export interface MapFailure {
  readonly kind: MapFailureKind;
  /** Codice HTTP, `0` per un errore di rete, `undefined` se non noto. */
  readonly status?: number;
  /** Riprovare non aiuta finché non cambia qualcosa (chiave, origine non autorizzata, richiesta non valida). */
  readonly permanent: boolean;
}

interface MapErrorEventLike {
  readonly error?: unknown;
  readonly sourceId?: string;
  readonly tile?: unknown;
}

const PERMANENT_STATUSES: ReadonlySet<number> = new Set([400, 401, 403]);

function errorField<Name extends string>(error: unknown, name: Name): unknown {
  return typeof error === "object" && error !== null && name in error ? (error as Record<Name, unknown>)[name] : undefined;
}

export function classifyMapError(event: MapErrorEventLike): MapFailure {
  const rawUrl = errorField(event.error, "url");
  const url = typeof rawUrl === "string" ? rawUrl : "";
  const rawStatus = errorField(event.error, "status");
  const status = typeof rawStatus === "number" ? rawStatus : undefined;
  const permanent = status !== undefined && PERMANENT_STATUSES.has(status);

  if (url === "") {
    // Senza URL non si sa cosa abbia fallito: un tassello (ha `tile`) si tratta come cartografia, il resto come sconosciuto.
    if (event.tile !== undefined) return { kind: "basemap", status, permanent };
    return { kind: "unknown", status, permanent };
  }
  if (/\/maps\/[^/]+\/style\.json/.test(url)) return { kind: "style", status, permanent };
  if (url.includes("/tiles/")) {
    // Un tassello che non esiste (404/204) è una zona senza dati, non un guasto.
    const isTile = event.tile !== undefined || /\/tiles\/[^/]+\/\d+\/\d+\/\d+/.test(url);
    if (isTile && (status === 404 || status === 204)) return { kind: "ignore", status, permanent: false };
    return { kind: "basemap", status, permanent };
  }
  if (url.includes("/sprites/") || url.includes("/fonts/")) return { kind: "cosmetic", status, permanent };
  return { kind: "ignore", status, permanent: false };
}

/** Attese prima di ogni tentativo automatico: due soli tentativi, mai un ciclo infinito. */
export const AUTO_RETRY_DELAYS_MS: readonly number[] = [1_500, 4_500];
/** Con 429 (troppe richieste) ritentare subito peggiora le cose: si aspetta di più. */
export const RATE_LIMIT_RETRY_DELAYS_MS: readonly number[] = [5_000, 12_000];

/** Quanto aspettare per il tentativo numero `attempt` (0 = il primo), oppure `null` se non si deve più riprovare da soli. */
export function nextRetryDelay(attempt: number, failure: Pick<MapFailure, "status" | "permanent">): number | null {
  if (failure.permanent) return null;
  const delays = failure.status === 429 ? RATE_LIMIT_RETRY_DELAYS_MS : AUTO_RETRY_DELAYS_MS;
  return delays[attempt] ?? null;
}

/** Frase per l'utente: dice cosa è successo senza gergo e, per un rifiuto del servizio, il codice che serve a chi cerca la causa. */
export function describeMapFailure(failure: Pick<MapFailure, "status" | "permanent"> | null): string {
  if (!failure) return "La mappa non si è caricata del tutto.";
  if (failure.status === 401 || failure.status === 403) {
    return `Il servizio cartografico ha rifiutato la richiesta (errore ${failure.status}). Finché non viene sistemato la mappa resta incompleta.`;
  }
  if (failure.permanent) return `Il servizio cartografico non accetta la richiesta (errore ${failure.status ?? "sconosciuto"}).`;
  if (failure.status === 429) return "Il servizio cartografico è sovraccarico in questo momento.";
  if (failure.status !== undefined && failure.status >= 500) return "Il servizio cartografico non risponde.";
  return "La connessione sembra assente o instabile: la mappa non si è caricata del tutto.";
}
