/**
 * Avvio dell'app: quando lo splash può lasciare spazio al contenuto.
 *
 * Lo splash non misura il tempo: aspetta segnali veri. Il router ha finito di inizializzarsi (pagine scaricate con `lazy` incluse) e, se
 * si atterra su Discover, la prima richiesta degli eventi si è conclusa (con un elenco o con un errore). La mappa NON è tra i segnali:
 * ha il suo caricamento (vedi `mapLoading.ts`), così lo splash non resta davanti per colpa del provider cartografico.
 *
 * Due reti di sicurezza: dopo `SPLASH_MAX_MS` lo splash se ne va comunque (un server lento o un errore non lo tengono davanti per sempre) e
 * dopo `SPLASH_HINT_MS` compare una frase che spiega l'attesa.
 *
 * Modulo senza dipendenze dal DOM (testabile con `node --test`).
 */
export const SPLASH_MAX_MS = 8_000;
export const SPLASH_HINT_MS = 4_000;
/** Durata della dissolvenza d'uscita (vedi `.linkr-splash` in splash.css). */
export const SPLASH_EXIT_MS = 240;

export interface BootSignals {
  /** Il router ha scaricato e inizializzato la prima pagina. */
  readonly routerReady: boolean;
  /** Discover ha ricevuto la risposta (positiva o no) alla prima richiesta degli eventi. */
  readonly discoverSettled: boolean;
  /** Tempo massimo scaduto o errore di struttura: si mostra comunque ciò che c'è. */
  readonly forced: boolean;
}

export function isBootReady(signals: BootSignals, expectsDiscover: boolean): boolean {
  if (signals.forced) return true;
  return signals.routerReady && (!expectsDiscover || signals.discoverSettled);
}

export interface BootStore {
  readonly getReady: () => boolean;
  readonly subscribe: (listener: () => void) => () => void;
  /** Segnali che si possono solo accendere: un segnale arrivato non torna indietro. */
  readonly mark: (signal: Partial<BootSignals>) => void;
}

export function createBootStore(expectsDiscover: boolean): BootStore {
  let signals: BootSignals = { routerReady: false, discoverSettled: false, forced: false };
  let ready = isBootReady(signals, expectsDiscover);
  const listeners = new Set<() => void>();

  return {
    getReady: () => ready,
    subscribe(listener) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
    mark(signal) {
      signals = {
        routerReady: signals.routerReady || signal.routerReady === true,
        discoverSettled: signals.discoverSettled || signal.discoverSettled === true,
        forced: signals.forced || signal.forced === true,
      };
      const next = isBootReady(signals, expectsDiscover);
      if (next === ready) return;
      ready = next;
      listeners.forEach((listener) => listener());
    },
  };
}

/** Segnale a un solo valore che si accende una volta (per esempio "lo splash è uscito"). */
export interface FlagStore {
  readonly get: () => boolean;
  readonly subscribe: (listener: () => void) => () => void;
  readonly raise: () => void;
}

export function createFlagStore(): FlagStore {
  let raised = false;
  const listeners = new Set<() => void>();
  return {
    get: () => raised,
    subscribe(listener) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
    raise() {
      if (raised) return;
      raised = true;
      listeners.forEach((listener) => listener());
    },
  };
}
