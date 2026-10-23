/**
 * Logica pura della bottom sheet della lista eventi (mobile): posizioni, regole del gesto e rilascio.
 * Nessuna dipendenza dal DOM, così si prova con `node --test`; il collegamento ai tocchi sta in `hooks/useBottomSheet.ts`.
 *
 * Il pannello ha sempre l'altezza dello stato espanso e si muove solo con `translateY`: l'"offset" è di quanto è stato spinto in basso.
 * 0 = espanso, `half` = intermedio, `closed` = tutto fuori (la sheet si smonta).
 */

export type SheetSnap = "full" | "half";
export type SheetTarget = SheetSnap | "closed";

export interface SheetOffsets {
  readonly full: 0;
  readonly half: number;
  readonly closed: number;
}

/** Quota dell'area mappa occupata dalla sheet nello stato intermedio, e altezza minima perché mostri header e almeno un evento. */
export const HALF_SHARE = 0.55;
export const HALF_MIN_PX = 320;
/** Se tra intermedio ed espanso restano meno pixel di così lo stato intermedio non ha senso (schermo basso, orizzontale): resta solo l'espanso. */
export const MIN_SNAP_GAP_PX = 56;
/** Movimento minimo prima di decidere se il gesto è della sheet o dello scorrimento. */
export const DRAG_SLOP_PX = 6;
/** Velocità (px/ms) oltre cui il rilascio è un lancio e vale più della posizione. */
export const FLICK_VELOCITY = 0.45;
/** Quanto avanti si proietta la posizione col movimento in corso, per un rilascio che "sente" la velocità. */
export const PROJECTION_MS = 140;

/** Offset di ogni stato dato l'altezza del pannello e l'altezza dell'area in cui sta (la mappa). */
export function sheetOffsets(panelHeight: number, areaHeight: number): SheetOffsets {
  const height = Math.max(0, Math.round(panelHeight));
  const halfVisible = Math.min(height, Math.max(HALF_MIN_PX, Math.round(areaHeight * HALF_SHARE)));
  const half = height - halfVisible;
  return { full: 0, half: half < MIN_SNAP_GAP_PX ? 0 : half, closed: height };
}

/** Sopra lo stato espanso la sheet resiste (effetto elastico); sotto la chiusura non va oltre. */
export function dampedOffset(raw: number, offsets: SheetOffsets): number {
  if (raw < 0) return -Math.pow(-raw, 0.7);
  return Math.min(raw, offsets.closed);
}

export interface ReleaseInput {
  readonly offset: number;
  /** px/ms, positivo = verso il basso. */
  readonly velocity: number;
  readonly from: SheetSnap;
  readonly offsets: SheetOffsets;
}

/** Dove va la sheet al rilascio: un lancio sposta di uno stato, altrimenti vince il punto più vicino alla posizione proiettata. */
export function resolveRelease({ offset, velocity, from, offsets }: ReleaseInput): SheetTarget {
  const hasHalf = offsets.half > 0;
  if (Math.abs(velocity) >= FLICK_VELOCITY) {
    if (velocity > 0) return from === "full" && hasHalf ? "half" : "closed";
    return "full";
  }

  const projected = offset + velocity * PROJECTION_MS;
  const candidates: ReadonlyArray<readonly [SheetTarget, number]> = hasHalf
    ? [["full", offsets.full], ["half", offsets.half], ["closed", offsets.closed]]
    : [["full", offsets.full], ["closed", offsets.closed]];
  let best = candidates[0];
  for (const candidate of candidates) {
    if (Math.abs(candidate[1] - projected) < Math.abs(best[1] - projected)) best = candidate;
  }
  return best[0];
}

export interface DragIntent {
  readonly snap: SheetSnap;
  /** `scrollTop` della lista all'inizio del gesto. */
  readonly scrollTop: number;
  /** Spostamento del dito dall'inizio: positivo = verso il basso. */
  readonly deltaY: number;
}

/**
 * Un gesto che parte sulla lista è della sheet o dello scorrimento?
 * - verso il basso: della sheet solo se la lista è già al primo elemento (altrimenti la lista scorre indietro);
 * - verso l'alto: della sheet finché non è espansa (prima si apre, poi si scorre).
 */
export function sheetOwnsListDrag({ snap, scrollTop, deltaY }: DragIntent): boolean {
  if (deltaY > 0) return scrollTop <= 0;
  if (deltaY < 0) return snap !== "full";
  return false;
}

export interface Sample {
  readonly y: number;
  readonly t: number;
}

/** Velocità del dito (px/ms) sugli ultimi ~100 ms di campioni; 0 se il dito si è fermato prima del rilascio. */
export function releaseVelocity(samples: readonly Sample[], now: number, windowMs = 100, stillMs = 80): number {
  const last = samples.at(-1);
  if (!last || now - last.t > stillMs) return 0;
  const first = samples.find((sample) => last.t - sample.t <= windowMs) ?? last;
  const elapsed = last.t - first.t;
  return elapsed > 0 ? (last.y - first.y) / elapsed : 0;
}
