/**
 * Quando la mappa di Discover va coperta dall'overlay LINKR.
 *
 * L'overlay serve solo al primo caricamento: finché la mappa non ha disegnato per la prima volta (`ready`, oppure `degraded` = disegnata ma
 * incompleta, con il suo avviso) l'utente non vede una cartografia a metà. Dopo, un tentativo di recupero, una selezione, un filtro o un
 * movimento della camera non coprono mai la mappa: il recupero usa la pillola discreta già esistente.
 *
 * `error` e `missing-key` non coprono: hanno la loro schermata con il messaggio e "Riprova". Premendo "Riprova" si torna a `loading` e,
 * poiché la mappa non ha ancora mai disegnato, l'overlay ricompare.
 *
 * Modulo senza dipendenze (testabile con `node --test`).
 */
export type MapLoadingStatus = "loading" | "ready" | "recovering" | "degraded" | "error" | "missing-key";

/** La mappa ha disegnato almeno una volta, completa o incompleta: da qui in poi l'overlay non serve più. */
export function hasRenderedOnce(status: MapLoadingStatus): boolean {
  return status === "ready" || status === "degraded";
}

/** `true` se, in questo stato e senza un disegno precedente, la mappa va coperta dall'overlay. */
export function coversMap(status: MapLoadingStatus, renderedBefore: boolean): boolean {
  return !renderedBefore && (status === "loading" || status === "recovering");
}

/** Testo sotto il simbolo: dice cosa sta succedendo senza promettere tempi. */
export function mapLoadingMessage(status: MapLoadingStatus, slow: boolean): string {
  if (slow) return "La mappa sta impiegando più del previsto…";
  return status === "recovering" ? "Ricarico la mappa…" : "Caricamento mappa";
}
