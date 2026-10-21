import { isAllowedEventImageUrl } from "./eventImages";
import { resolveEventImageAlt } from "./imageAlt";
import type { CreateEventPayload } from "./eventCreation";
import { MILAN_BOUNDS } from "./map";
import type { CompletedPlace } from "./placeDetails";
import { parseEventInstant } from "./romeTime";
import type { EventCategory, EventType } from "../types/event";

/**
 * Valori, validazione e payload condivisi dal form completo (`CreateEventPage`) e da Quick Create.
 * Le regole rispecchiano `EventRequest` del backend: ogni modifica va fatta qui, una volta sola.
 */
export interface EventFormValues {
  readonly title: string;
  readonly description: string;
  readonly category: EventCategory | "";
  readonly eventType: EventType;
  readonly startsAt: string;
  readonly endsAt: string;
  readonly venueName: string;
  readonly address: string;
  readonly neighbourhood: string;
  readonly latitude: string;
  readonly longitude: string;
  readonly imageUrl: string;
  readonly imageAlt: string;
  readonly imageCredit: string;
}

export type EventFormErrors = Partial<Record<keyof EventFormValues, string>>;

export const initialEventFormValues: EventFormValues = {
  title: "",
  description: "",
  category: "",
  eventType: "COMMUNITY",
  startsAt: "",
  endsAt: "",
  venueName: "",
  address: "",
  neighbourhood: "",
  latitude: "",
  longitude: "",
  imageUrl: "",
  imageAlt: "",
  imageCredit: "",
};

const MAX_EVENT_DURATION_MS = 7 * 24 * 60 * 60 * 1_000;

export const backendFieldMessages: Partial<Record<keyof EventFormValues, string>> = {
  title: "Il titolo deve contenere da 3 a 120 caratteri.",
  description: "Inserisci una descrizione di massimo 5.000 caratteri.",
  category: "Scegli una categoria valida.",
  startsAt: "La data di inizio deve essere valida e nel futuro.",
  endsAt: "La fine deve essere successiva all’inizio e distante al massimo 7 giorni.",
  venueName: "Inserisci un luogo di massimo 120 caratteri.",
  address: "Inserisci un indirizzo di massimo 200 caratteri.",
  neighbourhood: "Inserisci un quartiere di massimo 60 caratteri.",
  latitude: "La latitudine deve essere compresa tra 45.35 e 45.60.",
  longitude: "La longitudine deve essere compresa tra 8.95 e 9.45.",
  imageUrl: "Usa un URL HTTPS senza spazi, di massimo 500 caratteri.",
  imageAlt: "Inserisci un testo alternativo di massimo 200 caratteri quando usi un’immagine.",
  imageCredit: "I crediti dell’immagine non possono superare 120 caratteri.",
  eventType: "Scegli un tipo di evento consentito per il tuo account.",
};

/**
 * Applica indirizzo e quartiere trovati dal reverse geocoding, ma solo ai campi ancora vuoti e solo se il punto è ancora quello
 * per cui si è chiesto: una risposta in ritardo non deve scavalcare ciò che l'utente ha scritto o un luogo scelto dopo.
 */
export function fillPlaceDetails(
  values: EventFormValues,
  point: { readonly latitude: number; readonly longitude: number },
  completed: CompletedPlace,
): EventFormValues {
  if (values.latitude !== point.latitude.toFixed(6) || values.longitude !== point.longitude.toFixed(6)) return values;
  const address = !values.address.trim() && completed.address ? completed.address : values.address;
  const neighbourhood = !values.neighbourhood.trim() && completed.neighbourhood ? completed.neighbourhood : values.neighbourhood;
  if (address === values.address && neighbourhood === values.neighbourhood) return values;
  return { ...values, address, neighbourhood };
}

export function parseCoordinate(value: string): number | null {
  if (!value.trim()) return null;
  const coordinate = Number(value);
  return Number.isFinite(coordinate) ? coordinate : null;
}

export function validateEventForm(
  values: EventFormValues,
  originalStartsAt?: string,
  _hasSelectedImage = false,
): EventFormErrors {
  const errors: EventFormErrors = {};
  const title = values.title.trim();
  const description = values.description.trim();
  const venueName = values.venueName.trim();
  const address = values.address.trim();
  const neighbourhood = values.neighbourhood.trim();
  const latitude = parseCoordinate(values.latitude);
  const longitude = parseCoordinate(values.longitude);
  // `AAAA-MM-GGTHH:mm` (datetime-local) è ora di Milano, non del dispositivo; un ISO con `Z` è già un istante.
  const startsAt = values.startsAt ? parseEventInstant(values.startsAt) : Number.NaN;
  const endsAt = values.endsAt ? parseEventInstant(values.endsAt) : null;

  if (!title) errors.title = "Inserisci un titolo.";
  else if (title.length < 3) errors.title = "Il titolo deve contenere almeno 3 caratteri.";
  else if (title.length > 120) errors.title = "Il titolo non può superare 120 caratteri.";

  if (!description) errors.description = "Descrivi cosa succederà durante l’evento.";
  else if (description.length > 5_000) errors.description = "La descrizione non può superare 5.000 caratteri.";

  if (!values.category) errors.category = "Scegli una categoria.";

  if (!values.startsAt) errors.startsAt = "Scegli data e ora di inizio.";
  else if (!Number.isFinite(startsAt)) errors.startsAt = "Inserisci una data e un’ora valide. A Milano le 02:00–02:59 dell’ultima domenica di marzo non esistono.";
  else if (startsAt <= Date.now() && (!originalStartsAt || startsAt !== Date.parse(originalStartsAt))) {
    errors.startsAt = "L’evento deve iniziare nel futuro quando ne modifichi la data.";
  }

  if (endsAt !== null) {
    if (!Number.isFinite(endsAt)) errors.endsAt = "Inserisci una data e un’ora valide.";
    else if (Number.isFinite(startsAt) && endsAt <= startsAt) errors.endsAt = "La fine deve essere successiva all’inizio.";
    else if (Number.isFinite(startsAt) && endsAt - startsAt > MAX_EVENT_DURATION_MS) {
      errors.endsAt = "L’evento non può durare più di 7 giorni.";
    }
  }

  if (!venueName) errors.venueName = "Inserisci il nome del luogo.";
  else if (venueName.length > 120) errors.venueName = "Il luogo non può superare 120 caratteri.";

  if (!address) errors.address = "Inserisci l’indirizzo.";
  else if (address.length > 200) errors.address = "L’indirizzo non può superare 200 caratteri.";

  if (!neighbourhood) errors.neighbourhood = "Inserisci il quartiere.";
  else if (neighbourhood.length > 60) errors.neighbourhood = "Il quartiere non può superare 60 caratteri.";

  if (latitude === null) errors.latitude = "Inserisci una latitudine valida.";
  else if (latitude < MILAN_BOUNDS.minLatitude || latitude > MILAN_BOUNDS.maxLatitude) {
    errors.latitude = "La latitudine deve essere tra 45.35 e 45.60.";
  }

  if (longitude === null) errors.longitude = "Inserisci una longitudine valida.";
  else if (longitude < MILAN_BOUNDS.minLongitude || longitude > MILAN_BOUNDS.maxLongitude) {
    errors.longitude = "La longitudine deve essere tra 8.95 e 9.45.";
  }

  const imageUrl = values.imageUrl.trim();
  if (imageUrl.length > 500) errors.imageUrl = "L’URL dell’immagine non può superare 500 caratteri.";
  else if (imageUrl && !isAllowedEventImageUrl(imageUrl)) errors.imageUrl = "Inserisci un URL HTTPS senza spazi.";

  const imageAlt = values.imageAlt.trim();
  if (imageAlt.length > 200) errors.imageAlt = "Il testo alternativo non può superare 200 caratteri.";

  if (values.imageCredit.trim().length > 120) errors.imageCredit = "I crediti non possono superare 120 caratteri.";

  return errors;
}

/** `imageUrl` è l'URL finale (esterno oppure restituito dall'upload): il chiamante lo risolve prima di pubblicare. */
export function buildEventPayload(values: EventFormValues, imageUrl: string, type: EventType, fileName?: string): CreateEventPayload {
  const imageAlt = resolveEventImageAlt(values, imageUrl, fileName);
  return {
    title: values.title.trim(),
    description: values.description.trim(),
    category: values.category as EventCategory,
    startsAt: new Date(parseEventInstant(values.startsAt)).toISOString(),
    ...(values.endsAt ? { endsAt: new Date(parseEventInstant(values.endsAt)).toISOString() } : {}),
    venueName: values.venueName.trim(),
    address: values.address.trim(),
    neighbourhood: values.neighbourhood.trim(),
    latitude: Number(values.latitude),
    longitude: Number(values.longitude),
    ...(imageUrl ? { imageUrl } : {}),
    ...(imageAlt ? { imageAlt } : {}),
    ...(values.imageCredit.trim() ? { imageCredit: values.imageCredit.trim() } : {}),
    type,
  };
}

/** Errori di campo restituiti dal backend (400) tradotti nei campi del form. Il campo `type` del DTO è `eventType` nel form. */
export function mapServerFieldErrors(fieldErrors: Readonly<Record<string, string>> | undefined): EventFormErrors {
  const serverErrors: EventFormErrors = {};
  for (const field of Object.keys(fieldErrors ?? {})) {
    const formField = field === "type" ? "eventType" : field;
    if (formField in initialEventFormValues) {
      const typedField = formField as keyof EventFormValues;
      serverErrors[typedField] = backendFieldMessages[typedField] ?? "Controlla questo campo.";
    }
  }
  return serverErrors;
}
