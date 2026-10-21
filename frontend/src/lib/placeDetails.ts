/**
 * Dati di un luogo ricavati dalle risposte del geocoding MapTiler (ricerca e reverse).
 *
 * Modulo senza dipendenze: la geografia non si inventa mai. Un indirizzo o un quartiere entrano nel modulo solo se la risposta
 * li dichiara; un civico solo se ha la forma di un civico e appartiene al luogo scelto (non a un palazzo vicino).
 */
export type PlaceKind = "poi" | "place" | "address" | "other";

/** Quanto è preciso l'indirizzo: `exact` via e civico del luogo, `street` solo la via, `descriptive` un nome noto (parco, piazza, monumento). */
export type AddressQuality = "exact" | "street" | "descriptive";

export interface ParsedPlace {
  readonly id: string;
  readonly kind: PlaceKind;
  readonly venueName: string;
  readonly label: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly address?: string;
  readonly addressQuality?: AddressQuality;
  readonly neighbourhood?: string;
  /** Parchi, piazze, monumenti, attrazioni: luoghi senza un civico proprio, per cui basta un indirizzo descrittivo. */
  readonly landmark: boolean;
  readonly relevance: number;
}

/** Via (e civico, se attendibile) più vicina a un punto, da `types=address&limit=1` del reverse geocoding. */
export interface ReverseAddress {
  readonly street?: string;
  readonly houseNumber?: string;
  readonly neighbourhood?: string;
  /** Distanza in metri tra il punto cercato e l'indirizzo trovato. */
  readonly distanceMeters?: number;
}

/** Oltre queste distanze l'indirizzo trovato non descrive più il punto. Il civico è del palazzo più vicino: conta solo se è davvero accanto. */
export const REVERSE_HOUSE_NUMBER_MAX_M = 25;
export const REVERSE_STREET_MAX_M = 60;
export const REVERSE_LANDMARK_STREET_MAX_M = 160;

const NEIGHBOURHOOD_DESIGNATIONS = new Set(["neighbourhood", "quarter", "suburb"]);
const LANDMARK_LEISURE = new Set(["park", "garden", "playground", "common", "dog_park", "nature_reserve", "stadium", "sports_centre", "pitch"]);
const LANDMARK_TOURISM = new Set(["attraction", "viewpoint", "museum", "gallery", "artwork"]);
const LANDMARK_CATEGORIES = /\b(park|garden|square|plaza|piazza|monument|castle|cathedral|church|attraction|viewpoint|museum|theatre|theater|stadium)\b/i;
const STREET_PREFIX = /^(via|viale|piazza|piazzale|corso|largo|vicolo|alzaia|ripa|foro|strada|stradone|galleria|passaggio|lungo|bastioni|cavalcavia|circonvallazione|naviglio|vicoletto|rampa|riva|ponte|sottopasso|traversa|carrobbio|rotonda)\b/i;
/** Civici dalla forma normale: 39, 14a, 7/3, 12-14. `21n04` o `2c01` sono numerazioni interne e non si mostrano come civico. */
const HOUSE_NUMBER = /^\d{1,4}(?:\s?[A-Za-z]|[/-]\d{1,3}[A-Za-z]?)?$/;

type Json = Record<string, unknown>;

function isRecord(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed || undefined;
}

export function sanitizeHouseNumber(value: unknown): string | undefined {
  const candidate = text(value);
  return candidate && HOUSE_NUMBER.test(candidate) ? candidate : undefined;
}

/** Nei `context` le voci `road.*` hanno come testo un identificatore interno ("1_27487"): mai usarlo come nome di via. */
function isMeaningfulStreetName(value: string): boolean {
  return /\p{L}{3,}/u.test(value) && !/^[\d_\s-]+$/.test(value);
}

function coordinatePair(value: unknown): readonly [number, number] | null {
  if (!Array.isArray(value) || value.length < 2) return null;
  const [longitude, latitude] = value;
  if (typeof latitude !== "number" || typeof longitude !== "number") return null;
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  return [longitude, latitude];
}

function contextItems(feature: Json): readonly Json[] {
  return Array.isArray(feature.context) ? feature.context.filter(isRecord) : [];
}

function contextText(feature: Json, prefix: string): string | undefined {
  const item = contextItems(feature).find((candidate) => typeof candidate.id === "string" && candidate.id.startsWith(`${prefix}.`));
  return text(item?.text);
}

function featureTypes(feature: Json): readonly string[] {
  return Array.isArray(feature.place_type) ? feature.place_type.filter((type): type is string => typeof type === "string") : [];
}

function featureTags(feature: Json): Json {
  const properties = isRecord(feature.properties) ? feature.properties : {};
  return isRecord(properties.feature_tags) ? properties.feature_tags : {};
}

/** Il quartiere è la voce `place.*` del contesto che MapTiler marca come quartiere. Municipi e CAP non sono quartieri. */
export function neighbourhoodOf(feature: Json): string | undefined {
  const exact = contextItems(feature).find((item) => (
    typeof item.id === "string"
    && (item.id.startsWith("neighbourhood.") || item.id.startsWith("place."))
    && (item.id.startsWith("neighbourhood.") || (typeof item.place_designation === "string" && NEIGHBOURHOOD_DESIGNATIONS.has(item.place_designation)))
  ));
  return text(exact?.text);
}

function isNeighbourhoodFeature(feature: Json): boolean {
  const properties = isRecord(feature.properties) ? feature.properties : {};
  return featureTypes(feature).includes("neighbourhood")
    || (typeof properties.place_designation === "string" && NEIGHBOURHOOD_DESIGNATIONS.has(properties.place_designation));
}

function splitStreetAndNumber(value: string): { readonly street: string; readonly houseNumber?: string } {
  const match = /^(.+?)\s+(\d[\w/-]*)$/.exec(value.trim());
  if (!match) return { street: value.trim() };
  const houseNumber = sanitizeHouseNumber(match[2]);
  return houseNumber ? { street: match[1], houseNumber } : { street: match[1] };
}

function joinAddress(street: string, houseNumber?: string): string {
  return houseNumber ? `${street} ${houseNumber}` : street;
}

/**
 * Indirizzo del luogo stesso, in ordine di affidabilità: tag OSM `addr:*`, il pezzo di `place_name` che somiglia a una via,
 * la via nel contesto. Il civico c'è solo nei primi due casi e solo se ha una forma plausibile.
 */
function ownAddress(feature: Json, venueName: string): { readonly address: string; readonly quality: AddressQuality } | undefined {
  const tags = featureTags(feature);
  const taggedStreet = text(tags["addr:street"]);
  if (taggedStreet) {
    const houseNumber = sanitizeHouseNumber(tags["addr:housenumber"]);
    return { address: joinAddress(taggedStreet, houseNumber), quality: houseNumber ? "exact" : "street" };
  }

  const contextStreet = contextText(feature, "address");
  const segments = (text(feature.place_name) ?? "").split(",").map((segment) => segment.trim()).filter(Boolean);
  // Il primo pezzo è il nome; l'ultimo è il paese e il penultimo CAP e città: la via, se c'è, sta in mezzo.
  const middle = segments.slice(1, -2);
  const candidate = middle.find((segment) => {
    if (segment.toLowerCase() === venueName.toLowerCase()) return false;
    return STREET_PREFIX.test(segment) || (contextStreet !== undefined && segment.toLowerCase().startsWith(contextStreet.toLowerCase()));
  });
  if (candidate) {
    const { street, houseNumber } = splitStreetAndNumber(candidate);
    return { address: joinAddress(street, houseNumber), quality: houseNumber ? "exact" : "street" };
  }

  if (contextStreet && isMeaningfulStreetName(contextStreet)) return { address: contextStreet, quality: "street" };
  return undefined;
}

export function isLandmark(feature: Json): boolean {
  const tags = featureTags(feature);
  if (text(tags.historic) || text(tags.natural)) return true;
  const leisure = text(tags.leisure);
  if (leisure && LANDMARK_LEISURE.has(leisure)) return true;
  const tourism = text(tags.tourism);
  if (tourism && LANDMARK_TOURISM.has(tourism)) return true;
  const place = text(tags.place);
  if (place === "square" || place === "neighbourhood" || place === "quarter") return true;
  const properties = isRecord(feature.properties) ? feature.properties : {};
  const categories = Array.isArray(properties.categories) ? properties.categories.filter((item): item is string => typeof item === "string") : [];
  return categories.some((category) => LANDMARK_CATEGORIES.test(category)) || isNeighbourhoodFeature(feature);
}

/** Un risultato del geocoding diventato un luogo scegliibile; `null` se mancano nome, id o coordinate. */
export function parsePlaceFeature(value: unknown): ParsedPlace | null {
  if (!isRecord(value)) return null;
  const id = text(value.id);
  const name = text(value.text);
  const coordinates = coordinatePair(value.center) ?? (isRecord(value.geometry) && value.geometry.type === "Point" ? coordinatePair(value.geometry.coordinates) : null);
  if (!id || !name || !coordinates) return null;

  const [longitude, latitude] = coordinates;
  const types = featureTypes(value);
  const kind: PlaceKind = types.includes("poi") ? "poi" : types.includes("address") ? "address" : types.includes("place") || types.includes("neighbourhood") ? "place" : "other";
  const neighbourhood = isNeighbourhoodFeature(value) ? name : neighbourhoodOf(value);
  const landmark = isLandmark(value);
  const label = text(value.place_name) ?? name;
  const relevance = typeof value.relevance === "number" && Number.isFinite(value.relevance) ? value.relevance : 0;

  if (kind === "address") {
    // Una via cercata per nome: il luogo è la via (con civico, se l'utente l'ha digitato e MapTiler lo conosce).
    const number = sanitizeHouseNumber(value.address);
    const address = joinAddress(name, number);
    return { id, kind, venueName: address, label, latitude, longitude, address, addressQuality: number ? "exact" : "street", ...(neighbourhood ? { neighbourhood } : {}), landmark, relevance };
  }

  const own = kind === "poi" ? ownAddress(value, name) : undefined;
  return {
    id,
    kind,
    venueName: name,
    label,
    latitude,
    longitude,
    ...(own ? { address: own.address, addressQuality: own.quality } : {}),
    ...(neighbourhood ? { neighbourhood } : {}),
    landmark,
    relevance,
  };
}

/** Risultato del reverse (`types=address&limit=1`): via, civico e quartiere del punto, con la distanza dal punto cercato. */
export function parseReverseResponse(payload: unknown, point: { readonly latitude: number; readonly longitude: number }): ReverseAddress | null {
  if (!isRecord(payload) || !Array.isArray(payload.features)) return null;
  const feature = payload.features.find(isRecord);
  if (!feature) return null;

  const street = text(feature.text);
  const coordinates = coordinatePair(feature.center);
  const neighbourhood = neighbourhoodOf(feature);
  const houseNumber = sanitizeHouseNumber(feature.address);
  const distanceMeters = coordinates ? distanceInMeters(point, { longitude: coordinates[0], latitude: coordinates[1] }) : undefined;
  if (!street && !neighbourhood) return null;
  return {
    ...(street && isMeaningfulStreetName(street) ? { street } : {}),
    ...(houseNumber ? { houseNumber } : {}),
    ...(neighbourhood ? { neighbourhood } : {}),
    ...(distanceMeters !== undefined ? { distanceMeters: Math.round(distanceMeters) } : {}),
  };
}

export function distanceInMeters(
  a: { readonly latitude: number; readonly longitude: number },
  b: { readonly latitude: number; readonly longitude: number },
): number {
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180;
  const earthRadius = 6_371_000;
  const dLat = toRadians(b.latitude - a.latitude);
  const dLng = toRadians(b.longitude - a.longitude);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRadians(a.latitude)) * Math.cos(toRadians(b.latitude)) * Math.sin(dLng / 2) ** 2;
  return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Serve una seconda richiesta (reverse) solo se al luogo manca qualcosa che un indirizzo vicino può dare. */
export function needsReverse(place: Pick<ParsedPlace, "address" | "neighbourhood">): boolean {
  return place.address === undefined || place.neighbourhood === undefined;
}

export interface CompletedPlace {
  readonly address?: string;
  readonly addressQuality?: AddressQuality;
  readonly neighbourhood?: string;
}

/**
 * Completa un luogo con l'indirizzo vicino trovato dal reverse. Non sovrascrive mai ciò che il luogo dichiara già.
 * - Il civico del reverse si usa solo per un punto scelto sulla mappa e solo se è a pochi metri (`REVERSE_HOUSE_NUMBER_MAX_M`).
 * - Un luogo cercato per nome senza indirizzo proprio prende al massimo la via, e solo se vicina; per un parco, una piazza o un monumento si accetta più distanza.
 * - Un luogo di cui non si trova nulla di attendibile resta senza indirizzo: lo chiede l'interfaccia, non lo si inventa.
 */
export function completePlace(
  place: Pick<ParsedPlace, "address" | "addressQuality" | "neighbourhood" | "landmark" | "venueName">,
  reverse: ReverseAddress | null,
  origin: "search" | "map",
): CompletedPlace {
  let address = place.address;
  let addressQuality = place.addressQuality;
  const neighbourhood = place.neighbourhood ?? reverse?.neighbourhood;

  if (address === undefined && reverse?.street) {
    const distance = reverse.distanceMeters ?? Number.POSITIVE_INFINITY;
    const streetLimit = origin === "search" && place.landmark ? REVERSE_LANDMARK_STREET_MAX_M : REVERSE_STREET_MAX_M;
    if (distance <= streetLimit) {
      const withNumber = origin === "map" && reverse.houseNumber !== undefined && distance <= REVERSE_HOUSE_NUMBER_MAX_M;
      address = joinAddress(reverse.street, withNumber ? reverse.houseNumber : undefined);
      addressQuality = withNumber ? "exact" : "street";
    }
  }
  // Un monumento o un parco senza una via vicina: il nome stesso è un indirizzo descrittivo valido.
  if (address === undefined && origin === "search" && place.landmark) {
    address = place.venueName;
    addressQuality = "descriptive";
  }

  return {
    ...(address !== undefined ? { address, addressQuality } : {}),
    ...(neighbourhood !== undefined ? { neighbourhood } : {}),
  };
}
