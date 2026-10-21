import type { Map as MapLibreMap } from "maplibre-gl";

import { classifyMapError, describeMapFailure, nextRetryDelay } from "./mapHealth.ts";
import type { MapFailure } from "./mapHealth.ts";

/**
 * Controllore di salute di una mappa MapLibre: dice in che fase è (caricamento, pronta, in recupero, incompleta, in errore) e la
 * rimette in sesto quando un errore temporaneo l'ha lasciata senza cartografia.
 *
 * Fatti da cui parte (verificati nel browser, vedi `mapHealth.ts`): con tasselli falliti MapLibre emette `error` ma poi `load` e `idle`
 * come se nulla fosse, e non ritenta i tasselli falliti. Quindi "pronta" non basta: a ogni `idle` si controlla che la mappa disegni
 * davvero qualcosa e che nessun errore sia rimasto in sospeso.
 *
 * Regole: due tentativi automatici al massimo (con attesa crescente, più lunga con 429), nessun tentativo se l'errore è permanente
 * (401/403/400), poi la fase resta `degraded` con un messaggio e il pulsante "Riprova". Il recupero ricarica stile e tasselli sulla stessa
 * istanza (`reload`): la camera, i marker e la selezione non si toccano.
 *
 * Modulo senza dipendenze da MapLibre a runtime: riceve la mappa e le azioni, quindi si prova con una mappa finta (`node --test`).
 */
export type MapPhase =
  /** Lo stile o i primi dati non sono ancora arrivati. */
  | "loading"
  /** Sta impiegando più del previsto: si continua ad aspettare, ma si offre "Riprova". */
  | "slow"
  | "ready"
  /** C'è un tentativo di recupero in corso o in attesa. */
  | "recovering"
  /** Dopo i tentativi (o subito, se l'errore è permanente) la mappa resta incompleta: serve un'azione dell'utente. */
  | "degraded"
  /** La mappa non può partire (stile non scaricabile, WebGL non disponibile). */
  | "error";

export interface MapHealthState {
  readonly phase: MapPhase;
  readonly message: string | null;
  readonly failure: MapFailure | null;
}

interface MapLike {
  on(type: string, listener: (event: never) => void): unknown;
  off(type: string, listener: (event: never) => void): unknown;
  queryRenderedFeatures(): readonly unknown[];
}

/** Adatta una mappa MapLibre al contratto minimo del controllore (MapLibre tipa `on`/`off` evento per evento; qui servono cinque eventi noti). */
export function recoverableFrom(map: MapLibreMap): MapLike {
  const events = map as unknown as {
    on(type: string, listener: (event: never) => void): unknown;
    off(type: string, listener: (event: never) => void): unknown;
  };
  return {
    on: (type, listener) => events.on(type, listener),
    off: (type, listener) => events.off(type, listener),
    queryRenderedFeatures: () => map.queryRenderedFeatures(),
  };
}

interface RecoveryOptions {
  /** Ricarica stile e tasselli sulla stessa istanza (equivale a `setStyle(url, { diff: false })`). */
  readonly reload: () => void;
  /** Ricrea la mappa da zero: serve solo se non è mai partita o se il browser ha perso il contesto WebGL e non lo restituisce. */
  readonly recreate: () => void;
  readonly onChange: (state: MapHealthState) => void;
  /** `false` con Discover nascosto o il contenitore a dimensione zero: lì "nessuna feature" non significa mappa vuota. */
  readonly isVisible: () => boolean;
  readonly slowAfterMs?: number;
  readonly contextRestoreMs?: number;
}

export interface MapRecovery {
  /** Pulsante "Riprova": azzera i tentativi e ricarica (o ricrea, se la mappa non è mai partita). */
  readonly retry: () => void;
  readonly dispose: () => void;
}

const DEFAULT_SLOW_AFTER_MS = 20_000;
const DEFAULT_CONTEXT_RESTORE_MS = 5_000;

export function attachMapRecovery(map: MapLike, options: RecoveryOptions): MapRecovery {
  const slowAfterMs = options.slowAfterMs ?? DEFAULT_SLOW_AFTER_MS;
  const contextRestoreMs = options.contextRestoreMs ?? DEFAULT_CONTEXT_RESTORE_MS;

  let loaded = false;
  let disposed = false;
  let contextLost = false;
  let failures = 0;
  let lastFailure: MapFailure | null = null;
  let permanent = false;
  let attempts = 0;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let contextTimer: ReturnType<typeof setTimeout> | null = null;
  let state: MapHealthState = { phase: "loading", message: null, failure: null };

  function emit(phase: MapPhase, message: string | null = null, failure: MapFailure | null = null) {
    if (disposed || (state.phase === phase && state.message === message)) return;
    state = { phase, message, failure };
    options.onChange(state);
  }

  function clearRetry() {
    if (retryTimer !== null) clearTimeout(retryTimer);
    retryTimer = null;
  }

  const slowTimer = setTimeout(() => {
    if (!loaded && state.phase === "loading") emit("slow", "La mappa sta impiegando più del previsto.");
  }, slowAfterMs);

  function resetFailures() {
    failures = 0;
    lastFailure = null;
    permanent = false;
  }

  function handleIncomplete(failure: MapFailure | null) {
    const policy = { status: failure?.status, permanent };
    const delay = nextRetryDelay(attempts, policy);
    if (delay === null) {
      emit("degraded", describeMapFailure(failure ? { status: failure.status, permanent } : null), failure);
      return;
    }
    attempts += 1;
    emit("recovering", null, failure);
    clearRetry();
    retryTimer = setTimeout(() => {
      retryTimer = null;
      resetFailures();
      options.reload();
    }, delay);
  }

  function evaluate() {
    if (disposed || !loaded || contextLost) return;
    // Un tentativo è già in attesa: gli `idle` nel frattempo (es. un trascinamento) non devono accumularne altri.
    if (retryTimer !== null) return;

    const blank = options.isVisible() && map.queryRenderedFeatures().length === 0;
    if (failures === 0 && !blank) {
      attempts = 0;
      emit("ready");
      return;
    }
    handleIncomplete(failures > 0 ? lastFailure : null);
  }

  const onLoad = () => {
    loaded = true;
    clearTimeout(slowTimer);
    evaluate();
  };

  const onIdle = () => evaluate();

  const onError = (event: { readonly error?: unknown; readonly sourceId?: string; readonly tile?: unknown }) => {
    const failure = classifyMapError(event);
    if (failure.kind === "ignore") return;
    if (failure.kind === "style" || failure.kind === "unknown") {
      // Prima del caricamento la mappa non può partire; dopo, un errore senza URL non dice nulla di utile e lo si lascia in console.
      if (!loaded) emit("error", describeMapFailure(failure), failure);
      return;
    }
    failures += 1;
    lastFailure = failure;
    if (failure.permanent) permanent = true;
  };

  const onContextLost = () => {
    contextLost = true;
    emit("recovering", "Sto ripristinando la mappa…");
    if (contextTimer !== null) clearTimeout(contextTimer);
    contextTimer = setTimeout(() => {
      contextTimer = null;
      if (contextLost) emit("degraded", "Il browser ha interrotto la grafica della mappa e non l'ha ancora ripristinata.");
    }, contextRestoreMs);
  };

  const onContextRestored = () => {
    contextLost = false;
    if (contextTimer !== null) clearTimeout(contextTimer);
    contextTimer = null;
    // MapLibre ricrea da sé stile e risorse: si aspetta il suo `idle` per decidere.
    resetFailures();
  };

  map.on("load", onLoad);
  map.on("idle", onIdle);
  map.on("error", onError);
  map.on("webglcontextlost", onContextLost);
  map.on("webglcontextrestored", onContextRestored);

  return {
    retry() {
      clearRetry();
      attempts = 0;
      resetFailures();
      if (!loaded || contextLost || state.phase === "error") {
        options.recreate();
        return;
      }
      emit("recovering", null, null);
      options.reload();
    },
    dispose() {
      disposed = true;
      clearTimeout(slowTimer);
      clearRetry();
      if (contextTimer !== null) clearTimeout(contextTimer);
      map.off("load", onLoad);
      map.off("idle", onIdle);
      map.off("error", onError);
      map.off("webglcontextlost", onContextLost);
      map.off("webglcontextrestored", onContextRestored);
    },
  };
}
