import type { EventSummary } from "../types/event";

export const MILAN_CENTER: [number, number] = [9.19, 45.4642];
export const MILAN_INITIAL_ZOOM = 12;

export function getMapTilerStyleUrl(): string | null {
  const key = import.meta.env.VITE_MAPTILER_KEY?.trim();
  if (!key) return null;

  return `https://api.maptiler.com/maps/streets-v4/style.json?key=${encodeURIComponent(key)}`;
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
