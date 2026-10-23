/**
 * Tastiera virtuale e campo attivo: quanto è alta, quando lo spazio visibile è "poco", e di quanto scorrere perché il campo con il cursore resti
 * visibile. Puro e senza dipendenze (si prova con `node --test`): il DOM e `visualViewport` stanno in `hooks/useKeyboardInsets.ts`.
 *
 * Su iOS il layout viewport non si restringe con la tastiera: lo spazio realmente visibile è `visualViewport`.
 */

/** Sotto questa altezza coperta non è una tastiera ma la barra del browser che si ritrae. */
export const KEYBOARD_MIN_PX = 120;
/** Sotto questa altezza visibile un pannello con intestazione e CTA fisse lascerebbe al contenuto poche decine di pixel. */
export const COMPACT_VISIBLE_PX = 440;

export interface ViewportGeometry {
  readonly layoutHeight: number;
  readonly visualHeight: number;
  readonly visualOffsetTop: number;
  readonly scale: number;
}

/** Altezza coperta dalla tastiera, 0 se non c'è. Con lo zoom a pinch il viewport visivo si restringe senza tastiera: 0. */
export function keyboardHeight({ layoutHeight, visualHeight, visualOffsetTop, scale }: ViewportGeometry): number {
  if (scale > 1.01) return 0;
  const covered = Math.max(0, layoutHeight - visualHeight - visualOffsetTop);
  return covered > KEYBOARD_MIN_PX ? Math.round(covered) : 0;
}

/** Quota massima dello spazio visibile che intestazione e CTA fisse possono occupare: oltre, il corpo del pannello diventa un riquadro minuscolo. */
export const MAX_CHROME_SHARE = 0.4;

export function isCompactVisibleHeight(visibleHeight: number): boolean {
  return visibleHeight < COMPACT_VISIBLE_PX;
}

/**
 * Il pannello deve scorrere per intero (intestazione compresa, CTA incollata in fondo)? Sì se lo spazio visibile è poco, oppure se intestazione
 * e CTA ne occupano troppo: testo ingrandito (Dimensione del testo di Safari, Display Zoom) li fa crescere senza che lo schermo cresca.
 */
export function shouldUseCompactPanel(visibleHeight: number, chromeHeight: number): boolean {
  return isCompactVisibleHeight(visibleHeight) || chromeHeight > visibleHeight * MAX_CHROME_SHARE;
}

export interface RevealInput {
  readonly fieldTop: number;
  readonly fieldBottom: number;
  /** Fascia visibile entro cui il campo deve stare (già al netto di intestazioni, CTA o tastiera che la coprono). */
  readonly boundTop: number;
  readonly boundBottom: number;
  readonly margin?: number;
}

/**
 * Di quanto scorrere (positivo = verso il basso) perché il campo stia nella fascia visibile, con un margine. 0 se ci sta già.
 * Un campo più alto della fascia (es. un'area di testo) si allinea in alto: si vede l'inizio, dove c'è il cursore appena si scrive.
 */
export function revealDelta({ fieldTop, fieldBottom, boundTop, boundBottom, margin = 12 }: RevealInput): number {
  const top = boundTop + margin;
  const bottom = boundBottom - margin;
  if (bottom <= top) return 0;
  if (fieldBottom - fieldTop > bottom - top) return Math.round(fieldTop - top);
  if (fieldTop < top) return Math.round(fieldTop - top);
  if (fieldBottom > bottom) return Math.round(fieldBottom - bottom);
  return 0;
}

const NON_TEXT_INPUTS = new Set(["checkbox", "radio", "button", "submit", "reset", "file", "range", "color", "image", "hidden"]);

/** Un campo in cui si scrive (apre la tastiera): input di testo e simili, textarea, select, contenuto modificabile. */
export function isTextEntryField(tagName: string, type: string | null | undefined, contentEditable = false): boolean {
  const tag = tagName.toLowerCase();
  if (tag === "textarea" || tag === "select") return true;
  if (tag === "input") return !NON_TEXT_INPUTS.has((type ?? "text").toLowerCase());
  return contentEditable;
}
