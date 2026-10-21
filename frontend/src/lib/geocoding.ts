import { getMapTilerKey, MILAN_CENTER } from "./map";
import { parsePlaceFeature, parseReverseResponse } from "./placeDetails";
import type { ParsedPlace, ReverseAddress } from "./placeDetails";

const MILAN_BBOX = "8.95,45.35,9.45,45.60";
/** Quanti suggerimenti mostrare. Si chiedono un paio in più al servizio: i doppioni tra tipi diversi si scartano. */
const RESULT_LIMIT = 6;
const REQUEST_LIMIT = 8;
/** Un solo indice per POI, luoghi e indirizzi: MapTiler li ordina insieme per rilevanza (prima servivano due richieste per ogni ricerca). */
const SEARCH_TYPES = "poi,address,place";
const CACHE_TTL_MS = 10 * 60_000;
const CACHE_MAX_ENTRIES = 60;

/** Un luogo scelto dall'utente. Indirizzo e quartiere ci sono solo se il servizio li ha dichiarati (vedi `placeDetails.ts`). */
export type PlaceSuggestion = ParsedPlace;

export type PlaceSearchErrorCode = "missing-key" | "rate-limit" | "forbidden" | "network" | "invalid-response";

export class PlaceSearchError extends Error {
  constructor(readonly code: PlaceSearchErrorCode) {
    super(code);
    this.name = "PlaceSearchError";
  }
}

/** Piccola cache in memoria per sessione: tornare indietro con il backspace o riaprire lo stesso luogo non rifà la richiesta. */
class ResponseCache<Value> {
  private readonly entries = new Map<string, { readonly value: Value; readonly storedAt: number }>();

  get(key: string): Value | undefined {
    const entry = this.entries.get(key);
    if (!entry) return undefined;
    if (Date.now() - entry.storedAt > CACHE_TTL_MS) {
      this.entries.delete(key);
      return undefined;
    }
    // Riscrive la voce per tenerla in fondo all'ordine di inserimento (le più vecchie escono per prime).
    this.entries.delete(key);
    this.entries.set(key, entry);
    return entry.value;
  }

  set(key: string, value: Value) {
    this.entries.set(key, { value, storedAt: Date.now() });
    if (this.entries.size > CACHE_MAX_ENTRIES) {
      const oldest = this.entries.keys().next().value;
      if (oldest !== undefined) this.entries.delete(oldest);
    }
  }
}

const searchCache = new ResponseCache<readonly PlaceSuggestion[]>();
const reverseCache = new ResponseCache<ReverseAddress | null>();

async function requestGeocoding(
  pathSegment: string,
  params: Readonly<Record<string, string>>,
  key: string,
  signal?: AbortSignal,
): Promise<unknown> {
  const url = new URL(`https://api.maptiler.com/geocoding/${pathSegment}.json`);
  url.searchParams.set("key", key);
  url.searchParams.set("language", "it");
  for (const [name, value] of Object.entries(params)) url.searchParams.set(name, value);

  let response: Response;
  try {
    response = await fetch(url, { headers: { Accept: "application/json" }, signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new PlaceSearchError("network");
  }

  if (response.status === 429) throw new PlaceSearchError("rate-limit");
  if (response.status === 401 || response.status === 403) throw new PlaceSearchError("forbidden");
  if (!response.ok) throw new PlaceSearchError("network");

  try {
    return await response.json();
  } catch {
    throw new PlaceSearchError("invalid-response");
  }
}

function normalizeQuery(query: string): string {
  return query.trim().replace(/\s+/g, " ").toLowerCase();
}

export async function searchMilanPlaces(query: string, signal?: AbortSignal): Promise<readonly PlaceSuggestion[]> {
  const key = getMapTilerKey();
  if (!key) throw new PlaceSearchError("missing-key");

  const cacheKey = normalizeQuery(query);
  const cached = searchCache.get(cacheKey);
  if (cached) return cached;

  const payload = await requestGeocoding(encodeURIComponent(query.trim()), {
    autocomplete: "true",
    bbox: MILAN_BBOX,
    proximity: MILAN_CENTER.join(","),
    country: "it",
    types: SEARCH_TYPES,
    limit: String(REQUEST_LIMIT),
  }, key, signal);
  if (typeof payload !== "object" || payload === null) throw new PlaceSearchError("invalid-response");
  const features = (payload as { readonly features?: unknown }).features;
  if (!Array.isArray(features)) throw new PlaceSearchError("invalid-response");

  const normalizedQuery = query.trim();
  const seen = new Set<string>();
  const suggestions = features
    .flatMap((feature) => {
      const place = parsePlaceFeature(feature);
      return place ? [place] : [];
    })
    .sort((left, right) => {
      const leftExact = left.venueName.localeCompare(normalizedQuery, "it", { sensitivity: "base" }) === 0 ? 1 : 0;
      const rightExact = right.venueName.localeCompare(normalizedQuery, "it", { sensitivity: "base" }) === 0 ? 1 : 0;
      return rightExact - leftExact || right.relevance - left.relevance;
    })
    .filter((place) => {
      if (seen.has(place.id)) return false;
      seen.add(place.id);
      return true;
    })
    .slice(0, RESULT_LIMIT);

  searchCache.set(cacheKey, suggestions);
  return suggestions;
}

/**
 * Via e quartiere del punto, in una sola richiesta (`types=address&limit=1`: l'indirizzo più vicino, con il quartiere nel contesto).
 * `null` se il servizio non trova nulla vicino. Le risposte si ricordano per punto arrotondato a circa un metro.
 */
export async function reverseGeocodeMilan(
  latitude: number,
  longitude: number,
  signal?: AbortSignal,
): Promise<ReverseAddress | null> {
  const key = getMapTilerKey();
  if (!key) throw new PlaceSearchError("missing-key");

  const cacheKey = `${latitude.toFixed(5)},${longitude.toFixed(5)}`;
  const cached = reverseCache.get(cacheKey);
  if (cached !== undefined) return cached;

  const payload = await requestGeocoding(`${longitude.toFixed(6)},${latitude.toFixed(6)}`, { types: "address", limit: "1" }, key, signal);
  const reverse = parseReverseResponse(payload, { latitude, longitude });
  reverseCache.set(cacheKey, reverse);
  return reverse;
}

export function getPlaceSearchErrorMessage(error: unknown): string {
  if (error instanceof PlaceSearchError) {
    if (error.code === "missing-key") return "La ricerca luoghi non è configurata. Puoi compilare i dati manualmente.";
    if (error.code === "rate-limit") return "Troppe ricerche ravvicinate. Attendi qualche secondo e riprova.";
    if (error.code === "forbidden") return "La ricerca luoghi non è disponibile con la configurazione corrente.";
    if (error.code === "invalid-response") return "Il servizio luoghi ha restituito una risposta non valida. Riprova.";
  }
  return "Non è stato possibile cercare i luoghi. Controlla la connessione e riprova.";
}
