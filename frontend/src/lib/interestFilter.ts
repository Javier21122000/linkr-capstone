import type { EventCategory } from "../types/event";

/**
 * Dagli interessi dei profili ai filtri di Discover. Gli eventi hanno solo una categoria (nessun interesse o tag nell'API),
 * quindi un interesse si traduce in una categoria, mai in un filtro più fine: si mostrano solo eventi davvero pertinenti.
 */
interface InterestRef {
  readonly slug: string;
  readonly name: string;
  readonly categorySlug: string;
}

/** Categorie del catalogo interessi (V3) → categoria evento. "lifestyle" è eterogenea: si decide interesse per interesse. */
const CATEGORY_BY_INTEREST_CATEGORY: Readonly<Record<string, EventCategory>> = {
  music: "MUSIC",
  sport: "SPORT",
  "art-culture": "CULTURE",
  nightlife: "NIGHTLIFE",
  food: "FOOD",
};

/** Interessi singoli di "lifestyle" con una categoria evento sensata; gli altri (viaggi, tecnologia, moda, volontariato) non ne hanno. */
const CATEGORY_BY_INTEREST: Readonly<Record<string, EventCategory>> = {
  hiking: "OUTDOORS",
  languages: "SOCIAL",
  gaming: "SOCIAL",
};

const EVENT_CATEGORIES: readonly EventCategory[] = ["MUSIC", "FOOD", "CULTURE", "OUTDOORS", "NIGHTLIFE", "SPORT", "SOCIAL"];
const MAX_INTEREST_NAME = 40;

export function categoryForInterest(interest: Pick<InterestRef, "slug" | "categorySlug">): EventCategory | null {
  return CATEGORY_BY_INTEREST[interest.slug] ?? CATEGORY_BY_INTEREST_CATEGORY[interest.categorySlug] ?? null;
}

/** Indirizzo di Discover con il filtro già impostato: `/?categoria=MUSIC&interesse=UK%20Garage`. Senza categoria resta solo il nome, per dirlo all'utente. */
export function discoverPathForInterest(interest: InterestRef): string {
  const params = new URLSearchParams();
  const category = categoryForInterest(interest);
  if (category) params.set("categoria", category);
  params.set("interesse", interest.name.slice(0, MAX_INTEREST_NAME));
  return `/?${params.toString()}`;
}

export interface InterestFilterRequest {
  readonly category: EventCategory | null;
  readonly interestName: string;
}

/** Legge `categoria`/`interesse` dall'URL. Valori sconosciuti o malformati non sono una richiesta (mai filtri inventati dall'URL). */
export function readInterestFilterRequest(search: string): InterestFilterRequest | null {
  const params = new URLSearchParams(search);
  const name = params.get("interesse")?.trim().slice(0, MAX_INTEREST_NAME) ?? "";
  if (!name) return null;

  const rawCategory = params.get("categoria");
  if (rawCategory === null) return { category: null, interestName: name };
  const category = EVENT_CATEGORIES.find((candidate) => candidate === rawCategory);
  return category ? { category, interestName: name } : null;
}

/** Interessi condivisi per primi, poi gli altri; l'ordine di ciascun gruppo è quello ricevuto (catalogo). */
export function splitInterests<T extends { readonly id: string }>(
  interests: readonly T[],
  shared: readonly { readonly id: string }[],
): { readonly shared: readonly T[]; readonly others: readonly T[] } {
  const sharedIds = new Set(shared.map((interest) => interest.id));
  return {
    shared: interests.filter((interest) => sharedIds.has(interest.id)),
    others: interests.filter((interest) => !sharedIds.has(interest.id)),
  };
}
