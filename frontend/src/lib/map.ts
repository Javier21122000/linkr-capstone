import type { EventSummary } from "../types/event";

export const MILAN_CENTER: [number, number] = [9.19, 45.4642];
export const MILAN_INITIAL_ZOOM = 12;
export const MAP_MIN_ZOOM = 9;
export const MAP_MAX_ZOOM = 18;
/** Sopra 2× il canvas costa quanto una schermata in più senza che la cartografia si legga meglio (gli iPhone arrivano a 3×). */
export const MAP_MAX_PIXEL_RATIO = 2;

/** Area in cui il backend accetta eventi (`EventRequest`): fuori da qui la richiesta è rifiutata con 400. */
export const MILAN_BOUNDS = {
  minLatitude: 45.35,
  maxLatitude: 45.6,
  minLongitude: 8.95,
  maxLongitude: 9.45,
} as const;

export function isInsideMilan(latitude: number, longitude: number): boolean {
  return Number.isFinite(latitude)
    && Number.isFinite(longitude)
    && latitude >= MILAN_BOUNDS.minLatitude
    && latitude <= MILAN_BOUNDS.maxLatitude
    && longitude >= MILAN_BOUNDS.minLongitude
    && longitude <= MILAN_BOUNDS.maxLongitude;
}

/**
 * Un errore di MapLibre prima del primo caricamento è fatale solo se la mappa non può partire: lo stile non si scarica (rete, chiave,
 * rate limit) o manca WebGL. Un tassello, un glifo o uno sprite che falliscono non lo sono: la mappa si disegna lo stesso e li ritenta da sé.
 */
export function isFatalMapError(event: { readonly error?: unknown; readonly sourceId?: string; readonly tile?: unknown }): boolean {
  if (event.sourceId !== undefined || event.tile !== undefined) return false;
  const url = typeof event.error === "object" && event.error !== null && "url" in event.error ? String((event.error as { url?: unknown }).url) : "";
  // Con un URL, conta solo lo stile; senza URL (es. contesto WebGL) l'errore è fatale.
  return url === "" || url.includes("/style.json");
}

export function getMapTilerKey(): string | null {
  const key = import.meta.env.VITE_MAPTILER_KEY?.trim();
  return key || null;
}

/** Stile remoto condiviso su MapTiler: non si modifica. L'alleggerimento è locale (vedi `mapStyle.ts`). */
export function getMapTilerStyleUrl(): string | null {
  const key = getMapTilerKey();
  if (!key) return null;

  return `https://api.maptiler.com/maps/streets-v4/style.json?key=${encodeURIComponent(key)}`;
}

export interface PickedPoint {
  readonly latitude: number;
  readonly longitude: number;
}

/** Modalità "scegli un punto" della mappa di Discover: `null` = spenta, `point: null` = attiva ma ancora nessun punto toccato. */
export interface MapPickState {
  readonly point: PickedPoint | null;
}

export function hasValidCoordinates(event: EventSummary): boolean {
  return Number.isFinite(event.latitude)
    && Number.isFinite(event.longitude)
    && event.latitude >= -90
    && event.latitude <= 90
    && event.longitude >= -180
    && event.longitude <= 180
    && !(event.latitude === 0 && event.longitude === 0);
}
