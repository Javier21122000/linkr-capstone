import { LngLatBounds } from "maplibre-gl";
import type { Map as MapLibreMap } from "maplibre-gl";

import type { EventSummary } from "../types/event";
import { prefersReducedMotion } from "./motion";

/**
 * Inquadratura della mappa di Discover.
 *
 * Gli overlay sopra la mappa (navigazione, filtri, pannello lista, controlli, anteprima, Bottom Navigation)
 * dichiarano il bordo che occupano con `data-map-inset="top|right|bottom|left"`: la camera misura i loro
 * rettangoli reali invece di usare padding fissi, quindi resta corretta a qualsiasi viewport.
 */

/** Zoom massimo quando si inquadra un gruppo di eventi: oltre questo livello non si vedrebbe più il contesto urbano. */
export const FIT_MAX_ZOOM = 14.5;
/** Zoom minimo dell'inquadratura automatica: eventi molto lontani non devono ridurre Milano a un puntino. */
export const FIT_MIN_ZOOM = 10.5;
/** Zoom per un singolo evento: livello di quartiere, non di edificio. */
export const SINGLE_EVENT_ZOOM = 14;
/** Selezionando un evento non si scende mai sotto questo zoom (si può restare più vicini). */
export const SELECTION_MIN_ZOOM = 13;

const FRAMING_DURATION_MS = 650;
const SELECTION_DURATION_MS = 450;
/** Altezza del marker sopra il punto (il pin è ancorato in basso): serve spazio sopra, non sotto. */
const PIN_HEIGHT = 60;
const PIN_HALF_WIDTH = 26;
const EDGE_GAP = 18;
/** Gli overlay non possono togliere più di questa quota di una dimensione: sugli schermi bassi restano margini sani. */
const MAX_INSET_SHARE = 0.7;

type InsetEdge = "top" | "right" | "bottom" | "left";

export interface CameraInsets {
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
  readonly left: number;
}

export interface CameraTarget {
  readonly center: [number, number];
  readonly zoom: number;
  /** Spostamento in pixel dal centro del container: porta il punto al centro dell'area libera. */
  readonly offset: [number, number];
}

type Point = Pick<EventSummary, "latitude" | "longitude">;

function isInsetEdge(value: string | undefined): value is InsetEdge {
  return value === "top" || value === "right" || value === "bottom" || value === "left";
}

/** Quanto ogni bordo della mappa è coperto, in pixel, dagli overlay attualmente visibili. */
export function measureCameraInsets(container: HTMLElement): CameraInsets {
  const box = container.getBoundingClientRect();
  const insets = { top: 0, right: 0, bottom: 0, left: 0 };

  document.querySelectorAll<HTMLElement>("[data-map-inset]").forEach((element) => {
    const edge = element.dataset.mapInset;
    if (!isInsetEdge(edge)) return;

    const rect = element.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;
    // Un overlay fuori dal container (es. pagina nascosta) non toglie spazio alla mappa.
    if (rect.right <= box.left || rect.left >= box.right || rect.bottom <= box.top || rect.top >= box.bottom) return;

    if (edge === "top") insets.top = Math.max(insets.top, rect.bottom - box.top);
    if (edge === "bottom") insets.bottom = Math.max(insets.bottom, box.bottom - rect.top);
    if (edge === "left") insets.left = Math.max(insets.left, rect.right - box.left);
    if (edge === "right") insets.right = Math.max(insets.right, box.right - rect.left);
  });

  return insets;
}

/** Margini entro cui il *punto* di un marker deve cadere per risultare interamente visibile (pin incluso). */
function pointMargins(container: HTMLElement, insets: CameraInsets): CameraInsets {
  let left = insets.left + EDGE_GAP + PIN_HALF_WIDTH;
  let right = insets.right + EDGE_GAP + PIN_HALF_WIDTH;
  let top = insets.top + EDGE_GAP + PIN_HEIGHT;
  let bottom = insets.bottom + EDGE_GAP;

  const horizontalLimit = container.clientWidth * MAX_INSET_SHARE;
  const verticalLimit = container.clientHeight * MAX_INSET_SHARE;
  if (left + right > horizontalLimit) {
    const scale = horizontalLimit / (left + right);
    left *= scale;
    right *= scale;
  }
  if (top + bottom > verticalLimit) {
    const scale = verticalLimit / (top + bottom);
    top *= scale;
    bottom *= scale;
  }

  return { top, right, bottom, left };
}

function freeAreaOffset(margins: CameraInsets): [number, number] {
  return [(margins.left - margins.right) / 2, (margins.top - margins.bottom) / 2];
}

/** Dopo un cambio di layout (es. Discover tornato visibile) la mappa può avere ancora la vecchia dimensione. */
function syncMapSize(map: MapLibreMap, container: HTMLElement) {
  const canvas = map.getCanvas();
  if (canvas.clientWidth !== container.clientWidth || canvas.clientHeight !== container.clientHeight) map.resize();
}

/**
 * Camera che mostra tutti i punti nell'area libera della mappa.
 * Un solo punto → zoom urbano fisso; più punti → zoom del riquadro, tra FIT_MIN_ZOOM e FIT_MAX_ZOOM.
 */
export function planEventsFraming(map: MapLibreMap, container: HTMLElement, points: readonly Point[]): CameraTarget | null {
  if (points.length === 0) return null;
  syncMapSize(map, container);

  const margins = pointMargins(container, measureCameraInsets(container));
  const offset = freeAreaOffset(margins);

  if (points.length === 1) {
    const [point] = points;
    return { center: [point.longitude, point.latitude], zoom: SINGLE_EVENT_ZOOM, offset };
  }

  const bounds = new LngLatBounds();
  points.forEach((point) => bounds.extend([point.longitude, point.latitude]));

  const fitted = map.cameraForBounds(bounds, {
    padding: { top: margins.top, right: margins.right, bottom: margins.bottom, left: margins.left },
    maxZoom: FIT_MAX_ZOOM,
  });
  const zoom = Math.min(FIT_MAX_ZOOM, Math.max(FIT_MIN_ZOOM, fitted?.zoom ?? FIT_MIN_ZOOM));
  const center = bounds.getCenter();

  return { center: [center.lng, center.lat], zoom, offset };
}

/**
 * Camera per l'evento selezionato. Con `onlyIfHidden` (selezione dal marker) la mappa non si muove se il pin
 * è già interamente visibile nell'area libera: toccare marker vicini non deve far "ballare" la vista.
 */
export function planSelectionFraming(
  map: MapLibreMap,
  container: HTMLElement,
  point: Point,
  options: { readonly onlyIfHidden: boolean },
): CameraTarget | null {
  syncMapSize(map, container);

  const margins = pointMargins(container, measureCameraInsets(container));
  if (options.onlyIfHidden) {
    const projected = map.project([point.longitude, point.latitude]);
    const inside = projected.x >= margins.left
      && projected.x <= container.clientWidth - margins.right
      && projected.y >= margins.top
      && projected.y <= container.clientHeight - margins.bottom;
    if (inside) return null;
  }

  return {
    center: [point.longitude, point.latitude],
    zoom: Math.max(map.getZoom(), SELECTION_MIN_ZOOM),
    offset: freeAreaOffset(margins),
  };
}

interface ApplyOptions {
  readonly animate: boolean;
  readonly kind: "framing" | "selection";
}

/** Una sola transizione alla volta: `easeTo` sostituisce quella in corso, quindi non si accumulano animazioni. */
export function applyCameraTarget(map: MapLibreMap, target: CameraTarget, { animate, kind }: ApplyOptions) {
  const baseDuration = kind === "framing" ? FRAMING_DURATION_MS : SELECTION_DURATION_MS;
  map.easeTo({
    center: target.center,
    zoom: target.zoom,
    offset: target.offset,
    duration: animate && !prefersReducedMotion() ? baseDuration : 0,
    essential: true,
  });
}
