import { getMapTilerKey, MILAN_CENTER } from "./map";

const MILAN_BBOX = "8.95,45.35,9.45,45.60";
const RESULT_LIMIT = 6;

export interface PlaceSuggestion {
  readonly id: string;
  readonly venueName: string;
  readonly label: string;
  readonly address?: string;
  readonly neighbourhood?: string;
  readonly latitude: number;
  readonly longitude: number;
}

export type PlaceSearchErrorCode = "missing-key" | "rate-limit" | "forbidden" | "network" | "invalid-response";

export class PlaceSearchError extends Error {
  constructor(readonly code: PlaceSearchErrorCode) {
    super(code);
    this.name = "PlaceSearchError";
  }
}

interface MapTilerContextItem {
  readonly id?: unknown;
  readonly text?: unknown;
  readonly place_designation?: unknown;
}

interface MapTilerFeature {
  readonly id?: unknown;
  readonly text?: unknown;
  readonly place_name?: unknown;
  readonly place_type?: unknown;
  readonly address?: unknown;
  readonly relevance?: unknown;
  readonly center?: unknown;
  readonly geometry?: {
    readonly type?: unknown;
    readonly coordinates?: unknown;
  };
  readonly context?: unknown;
  readonly properties?: {
    readonly feature_tags?: unknown;
    readonly place_designation?: unknown;
  };
}

interface MapTilerFeatureCollection {
  readonly features?: unknown;
}

interface RankedPlaceSuggestion extends PlaceSuggestion {
  readonly relevance: number;
}

function nonEmptyString(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed || undefined;
}

function coordinatePair(value: unknown): readonly [number, number] | null {
  if (!Array.isArray(value) || value.length < 2) return null;
  const [longitude, latitude] = value;
  if (typeof latitude !== "number" || typeof longitude !== "number") return null;
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  return [longitude, latitude];
}

function featureTypes(feature: MapTilerFeature): readonly string[] {
  return Array.isArray(feature.place_type)
    ? feature.place_type.filter((value): value is string => typeof value === "string")
    : [];
}

function contextItems(feature: MapTilerFeature): readonly MapTilerContextItem[] {
  return Array.isArray(feature.context)
    ? feature.context.filter((item): item is MapTilerContextItem => typeof item === "object" && item !== null)
    : [];
}

function contextText(feature: MapTilerFeature, prefix: string): string | undefined {
  const item = contextItems(feature).find((candidate) => (
    typeof candidate.id === "string" && candidate.id.startsWith(`${prefix}.`)
  ));
  return nonEmptyString(item?.text);
}

function returnedNeighbourhood(feature: MapTilerFeature, venueName: string): string | undefined {
  if (featureTypes(feature).includes("neighbourhood")) return venueName;
  const exactNeighbourhood = contextText(feature, "neighbourhood");
  if (exactNeighbourhood) return exactNeighbourhood;

  const neighbourhoodDesignations = new Set(["neighbourhood", "quarter", "suburb"]);
  const place = contextItems(feature).find((item) => (
    typeof item.place_designation === "string" && neighbourhoodDesignations.has(item.place_designation)
  ));
  return nonEmptyString(place?.text);
}

function featureTag(feature: MapTilerFeature, key: string): string | undefined {
  const tags = feature.properties?.feature_tags;
  if (typeof tags !== "object" || tags === null) return undefined;
  return nonEmptyString((tags as Record<string, unknown>)[key]);
}

function returnedAddress(feature: MapTilerFeature, venueName: string): string | undefined {
  const taggedStreet = featureTag(feature, "addr:street");
  const taggedHouseNumber = featureTag(feature, "addr:housenumber");
  if (taggedStreet) return [taggedStreet, taggedHouseNumber].filter(Boolean).join(" ");

  const address = nonEmptyString(feature.address);
  if (!address) return featureTypes(feature).includes("address") ? venueName : undefined;
  if (!/^\d/.test(address)) return address;

  const road = contextText(feature, "road");
  if (road) return `${road} ${address}`;
  if (featureTypes(feature).includes("address")) return `${venueName} ${address}`;
  return address;
}

function toSuggestion(value: unknown): RankedPlaceSuggestion | null {
  if (typeof value !== "object" || value === null) return null;
  const feature = value as MapTilerFeature;
  const id = nonEmptyString(feature.id);
  const venueName = nonEmptyString(feature.text);
  const coordinates = coordinatePair(feature.center)
    ?? (feature.geometry?.type === "Point" ? coordinatePair(feature.geometry.coordinates) : null);
  if (!id || !venueName || !coordinates) return null;

  const [longitude, latitude] = coordinates;
  const neighbourhood = returnedNeighbourhood(feature, venueName);
  const address = returnedAddress(feature, venueName);

  return {
    id,
    venueName,
    label: nonEmptyString(feature.place_name) ?? venueName,
    ...(address ? { address } : {}),
    ...(neighbourhood ? { neighbourhood } : {}),
    latitude,
    longitude,
    relevance: typeof feature.relevance === "number" && Number.isFinite(feature.relevance) ? feature.relevance : 0,
  };
}

async function requestPlaces(
  query: string,
  key: string,
  types: string | undefined,
  signal?: AbortSignal,
): Promise<readonly RankedPlaceSuggestion[]> {
  const url = new URL(`https://api.maptiler.com/geocoding/${encodeURIComponent(query.trim())}.json`);
  url.searchParams.set("key", key);
  url.searchParams.set("autocomplete", "true");
  url.searchParams.set("bbox", MILAN_BBOX);
  url.searchParams.set("proximity", MILAN_CENTER.join(","));
  url.searchParams.set("country", "it");
  url.searchParams.set("language", "it");
  if (types) url.searchParams.set("types", types);
  url.searchParams.set("limit", String(RESULT_LIMIT));

  let response: Response;
  try {
    response = await fetch(url, {
      headers: { Accept: "application/json" },
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new PlaceSearchError("network");
  }

  if (response.status === 429) throw new PlaceSearchError("rate-limit");
  if (response.status === 401 || response.status === 403) throw new PlaceSearchError("forbidden");
  if (!response.ok) throw new PlaceSearchError("network");

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new PlaceSearchError("invalid-response");
  }
  if (typeof payload !== "object" || payload === null) throw new PlaceSearchError("invalid-response");
  const features = (payload as MapTilerFeatureCollection).features;
  if (!Array.isArray(features)) throw new PlaceSearchError("invalid-response");

  return features.flatMap((feature) => {
    const suggestion = toSuggestion(feature);
    return suggestion ? [suggestion] : [];
  });
}

export async function searchMilanPlaces(query: string, signal?: AbortSignal): Promise<readonly PlaceSuggestion[]> {
  const key = getMapTilerKey();
  if (!key) throw new PlaceSearchError("missing-key");

  // L'indice predefinito privilegia indirizzi e luoghi geografici; la ricerca POI va abilitata esplicitamente.
  const [places, pointsOfInterest] = await Promise.all([
    requestPlaces(query, key, undefined, signal),
    requestPlaces(query, key, "poi", signal),
  ]);
  const seen = new Set<string>();
  const normalizedQuery = query.trim();
  return [...places, ...pointsOfInterest]
    .sort((left, right) => {
      const leftExact = left.venueName.localeCompare(normalizedQuery, "it", { sensitivity: "base" }) === 0 ? 1 : 0;
      const rightExact = right.venueName.localeCompare(normalizedQuery, "it", { sensitivity: "base" }) === 0 ? 1 : 0;
      return rightExact - leftExact || right.relevance - left.relevance;
    })
    .flatMap(({ relevance: _relevance, ...suggestion }) => {
      if (seen.has(suggestion.id)) return [];
      seen.add(suggestion.id);
      return [suggestion];
    })
    .slice(0, RESULT_LIMIT);
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
