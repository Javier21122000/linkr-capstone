/**
 * Geometria pura della camera per l'evento selezionato (senza MapLibre, testabile con `node --test`).
 * I margini sono quelli del *punto* del marker (pin incluso) dentro l'area libera dagli overlay, in pixel dal bordo della mappa.
 */
export interface FramingMargins {
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
  readonly left: number;
}

export interface ScreenPoint {
  readonly x: number;
  readonly y: number;
}

/** Entro questa distanza (px) il marker è già dove la camera lo metterebbe: muoverla sarebbe solo un tremolio. */
export const FRAMING_TOLERANCE_PX = 2;

/** Dove deve stare il punto del marker sullo schermo: il centro dell'area libera tra pannello, filtri, controlli e anteprima. */
export function selectionScreenTarget(size: { readonly width: number; readonly height: number }, margins: FramingMargins): ScreenPoint {
  return {
    x: margins.left + (size.width - margins.left - margins.right) / 2,
    y: margins.top + (size.height - margins.top - margins.bottom) / 2,
  };
}

/** Il marker è già al centro dell'area libera (e non c'è uno zoom da correggere)? Allora nessuna animazione. */
export function isAlreadyFramed(projected: ScreenPoint, target: ScreenPoint, tolerance: number = FRAMING_TOLERANCE_PX): boolean {
  return Math.abs(projected.x - target.x) <= tolerance && Math.abs(projected.y - target.y) <= tolerance;
}
