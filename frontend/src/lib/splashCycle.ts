/**
 * Il ciclo del logo nello splash di avvio: quando può uscire senza interrompere il movimento.
 *
 * Il logo si apre e si chiude in cicli di `SPLASH_CYCLE_MS` (fotogrammi in splash.css). Quando l'app è pronta lo splash non si ferma a metà
 * chiusura: aspetta che le mani siano tornate aperte (dal `SPLASH_REST_FROM` del ciclo in poi, con il wordmark leggibile), poi sfuma.
 *  - Primo avvio sul dispositivo: si mostra almeno un ciclo completo.
 *  - Avvii successivi: il movimento parte dopo `SPLASH_RETURN_DELAY_MS`; se l'app è pronta prima, o durante la breve sosta iniziale a mani
 *    aperte (`SPLASH_HEAD_HOLD`), si esce subito e il movimento non compare nemmeno.
 *
 * Modulo senza dipendenze dal DOM (testabile con `node --test`).
 */
export const SPLASH_CYCLE_MS = 1_600;
/** Da qui in poi nel ciclo le mani sono di nuovo aperte (fine dell'assestamento). */
export const SPLASH_REST_FROM = 0.9;
/** All'inizio del ciclo le mani restano aperte per questa frazione prima di chiudersi. */
export const SPLASH_HEAD_HOLD = 0.1;
/** Avvii successivi: il logo resta fermo e aperto per questo tempo prima del primo ciclo. */
export const SPLASH_RETURN_DELAY_MS = 350;

/**
 * Millisecondi da attendere, da adesso, prima di avviare l'uscita (le mani devono essere aperte).
 * `elapsedMs` = tempo trascorso da quando lo splash è comparso.
 */
export function splashExitDelayMs(elapsedMs: number, firstRun: boolean, startDelayMs: number = firstRun ? 0 : SPLASH_RETURN_DELAY_MS): number {
  const sinceStart = elapsedMs - startDelayMs;
  if (sinceStart < 0) return 0;
  const cycles = Math.floor(sinceStart / SPLASH_CYCLE_MS);
  const position = sinceStart - cycles * SPLASH_CYCLE_MS;
  const restAt = SPLASH_REST_FROM * SPLASH_CYCLE_MS;
  const openAtStart = !(firstRun && cycles === 0) && position < SPLASH_HEAD_HOLD * SPLASH_CYCLE_MS;
  if (openAtStart || position >= restAt) return 0;
  return Math.round(restAt - position);
}

/** Chiave del dispositivo: il primo avvio mostra il ciclo intero, i successivi vanno al sodo. */
export const SPLASH_SEEN_KEY = "linkr:splash:seen:v1";

export interface SplashStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

/** `true` se questo dispositivo non ha mai completato lo splash. Senza storage (navigazione privata) si considera già visto: avvio rapido. */
export function isFirstSplashRun(storage: SplashStorage | null): boolean {
  if (!storage) return false;
  try {
    return storage.getItem(SPLASH_SEEN_KEY) === null;
  } catch {
    return false;
  }
}

export function markSplashSeen(storage: SplashStorage | null): void {
  try {
    storage?.setItem(SPLASH_SEEN_KEY, "1");
  } catch {
    // Storage pieno o bloccato: al prossimo avvio si rivedrà il ciclo, nulla di grave.
  }
}
