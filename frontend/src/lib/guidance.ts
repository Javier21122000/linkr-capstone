/**
 * Logica pura del benvenuto e del tour: quando mostrarli, cosa ricordare e dove mettere il callout rispetto all'elemento evidenziato.
 * Nessuna dipendenza (testabile con `node --test`): il DOM sta tutto in `ProductGuidanceContext`.
 */

/** Stessa chiave della persistenza già esistente: `complete` = il benvenuto non si ripresenta da solo. */
export const ONBOARDING_KEY_PREFIX = "linkr:onboarding:v1:";
/** Dopo che Discover è pronto si aspetta un istante: l'utente guarda la mappa prima di essere interrotto. */
export const WELCOME_DELAY_MS = 1_000;

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export function onboardingKey(userId: string | null): string {
  return `${ONBOARDING_KEY_PREFIX}${userId ? `user:${userId}` : "anonymous"}`;
}

/**
 * Il benvenuto è già stato visto? Chi ha scelto "Non ora" (o ha fatto il tour) da anonimo non lo rivede nemmeno dopo l'accesso sullo
 * stesso browser: era già la sua prima visita. Senza storage disponibile non si insiste: nessuna guida automatica.
 */
export function isOnboardingComplete(storage: StorageLike | null, userId: string | null): boolean {
  if (!storage) return true;
  try {
    return storage.getItem(onboardingKey(null)) === "complete" || (userId !== null && storage.getItem(onboardingKey(userId)) === "complete");
  } catch {
    return true;
  }
}

export function markOnboardingComplete(storage: StorageLike | null, userId: string | null): void {
  try {
    storage?.setItem(onboardingKey(userId), "complete");
  } catch {
    // Storage non scrivibile: la guida resta comunque utilizzabile nella sessione corrente.
  }
}

/** Stato della mappa di Discover (`data-status`) dopo cui non c'è più nulla da aspettare: pronta, incompleta, in errore o non configurata. */
const SETTLED_MAP_STATUSES: ReadonlySet<string> = new Set(["ready", "degraded", "error", "missing-key"]);

/** Discover è pronto quando è la pagina visibile, gli eventi sono arrivati (o hanno fallito) e la mappa ha finito di caricare (o di fallire). */
export function isDiscoverReady(state: { readonly active: boolean; readonly eventsLoading: boolean; readonly mapStatus: string }): boolean {
  return state.active && !state.eventsLoading && SETTLED_MAP_STATUSES.has(state.mapStatus);
}

// ——— Posizionamento del callout del tour ———

export interface Rect {
  readonly left: number;
  readonly top: number;
  readonly width: number;
  readonly height: number;
}

export interface Size {
  readonly width: number;
  readonly height: number;
}

export interface Viewport extends Size {
  /** Spazio occupato in alto e in basso da barre di sistema (safe area). */
  readonly insetTop: number;
  readonly insetBottom: number;
}

export type CalloutSide = "top" | "bottom" | "left" | "right" | "center";

export interface TourPlacement {
  readonly side: CalloutSide;
  readonly left: number;
  readonly top: number;
  /** Posizione della punta della freccia lungo il lato del callout rivolto verso l'elemento, in pixel dal suo angolo. `null` senza freccia. */
  readonly arrow: number | null;
}

const ARROW_EDGE = 20;

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

/**
 * Dove mettere il callout: il lato dell'elemento con più spazio (prima sotto se l'elemento sta nella metà alta, prima sopra se sta in quella
 * bassa, poi ai lati). Resta sempre dentro la finestra e la freccia punta al centro dell'elemento. Senza elemento (o se nessun lato ha spazio)
 * il callout va in basso al centro, senza freccia.
 */
export function placeTourCard(target: Rect | null, card: Size, viewport: Viewport, gap = 16, margin = 12): TourPlacement {
  const minLeft = margin;
  const maxLeft = viewport.width - card.width - margin;
  const minTop = viewport.insetTop + margin;
  const maxTop = viewport.height - viewport.insetBottom - card.height - margin;
  const centered = clamp((viewport.width - card.width) / 2, minLeft, maxLeft);
  const fallback: TourPlacement = { side: "center", left: centered, top: clamp(maxTop, minTop, maxTop), arrow: null };
  if (!target) return fallback;
  // Se nessun lato è abbastanza libero il callout va comunque nella metà opposta a quella dell'elemento, per coprirlo il meno possibile.
  const targetInUpperHalf = target.top + target.height / 2 < viewport.height / 2;
  const opposite: TourPlacement = { side: "center", left: centered, top: clamp(targetInUpperHalf ? maxTop : minTop, minTop, maxTop), arrow: null };

  const centerX = target.left + target.width / 2;
  const centerY = target.top + target.height / 2;
  const spaceAbove = target.top - viewport.insetTop - margin;
  const spaceBelow = viewport.height - viewport.insetBottom - (target.top + target.height) - margin;
  const spaceLeft = target.left - margin;
  const spaceRight = viewport.width - (target.left + target.width) - margin;

  const preferTop = centerY > viewport.height / 2;
  const order: readonly CalloutSide[] = preferTop ? ["top", "bottom", "right", "left"] : ["bottom", "top", "right", "left"];

  for (const side of order) {
    if (side === "top" && spaceAbove >= card.height + gap) {
      const left = clamp(centerX - card.width / 2, minLeft, maxLeft);
      return { side, left, top: target.top - gap - card.height, arrow: clamp(centerX - left, ARROW_EDGE, card.width - ARROW_EDGE) };
    }
    if (side === "bottom" && spaceBelow >= card.height + gap) {
      const left = clamp(centerX - card.width / 2, minLeft, maxLeft);
      return { side, left, top: target.top + target.height + gap, arrow: clamp(centerX - left, ARROW_EDGE, card.width - ARROW_EDGE) };
    }
    if (side === "right" && spaceRight >= card.width + gap) {
      const top = clamp(centerY - card.height / 2, minTop, maxTop);
      return { side, left: target.left + target.width + gap, top, arrow: clamp(centerY - top, ARROW_EDGE, card.height - ARROW_EDGE) };
    }
    if (side === "left" && spaceLeft >= card.width + gap) {
      const top = clamp(centerY - card.height / 2, minTop, maxTop);
      return { side, left: target.left - gap - card.width, top, arrow: clamp(centerY - top, ARROW_EDGE, card.height - ARROW_EDGE) };
    }
  }
  return opposite;
}
