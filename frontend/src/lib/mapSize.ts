/**
 * Dimensione della mappa rispetto al suo contenitore (modulo senza dipendenze, testabile con `node --test`).
 *
 * Con Discover nascosto il contenitore misura 0×0 e MapLibre, se ridimensiona in quel momento, ripiega su un canvas di 400×300. Di norma il
 * `ResizeObserver` lo riporta alla dimensione giusta quando Discover torna visibile, ma non sempre: la chiamata finale del throttle di MapLibre
 * (50 ms) può scattare mentre Discover è nascosto, e se Discover torna visibile prima che il browser disegni un fotogramma, per l'osservatore
 * la dimensione non è cambiata e non notifica più. Il risultato era la cartografia disegnata in un rettangolo di 400×300 e il resto vuoto.
 */
export interface Size {
  readonly width: number;
  readonly height: number;
}

/** Quanto dopo il ritorno di Discover si controlla la dimensione: lascia al `ResizeObserver` il tempo di fare il suo lavoro per primo. */
export const MAP_SIZE_CHECK_DELAY_MS = 200;

/** Il canvas ha una larghezza CSS frazionaria (buffer arrotondato diviso il pixel ratio), il contenitore un intero: 1 px di scarto non conta. */
const TOLERANCE_PX = 1;

/** `true` se il contenitore è visibile e il canvas non ne ha la dimensione: serve `map.resize()`. Con il contenitore a 0×0 (nascosto) mai. */
export function canvasNeedsResize(canvas: Size, container: Size): boolean {
  if (container.width <= 0 || container.height <= 0) return false;
  return Math.abs(canvas.width - container.width) > TOLERANCE_PX || Math.abs(canvas.height - container.height) > TOLERANCE_PX;
}
