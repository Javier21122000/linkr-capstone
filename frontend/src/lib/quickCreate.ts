import { fillPlaceDetails, initialEventFormValues, parseCoordinate, validateEventForm } from "./eventForm";
import type { EventFormErrors, EventFormValues } from "./eventForm";
import type { PlaceSuggestion } from "./geocoding";
import { isInsideMilan } from "./map";
import type { CompletedPlace } from "./placeDetails";
import { checkSchedule } from "./quickCreateSchedule";
import type { AmbiguousChoice, QuickDateChoice, ScheduleIssue } from "./quickCreateSchedule";

/**
 * Stato e regole di Quick Create. Non esiste un secondo modello di evento: la bozza contiene gli stessi
 * `EventFormValues` del form completo e la validazione passa da `validateEventForm`; qui si aggiungono solo
 * la scelta guidata di data/ora e la suddivisione degli errori nei tre passaggi.
 */
export type QuickStep = 1 | 2 | 3;

export interface QuickCreateDraft {
  readonly values: EventFormValues;
  /** `search`: suggerimento del geocoder (coordinate legate al nome scelto). `map`: punto indicato sulla mappa. */
  readonly placeSource: "search" | "map" | null;
  readonly dateChoice: QuickDateChoice | null;
  /** `AAAA-MM-GG`, usata solo con `dateChoice === "custom"`. */
  readonly customDate: string;
  /** `HH:mm`, ora di Milano. Mai precompilata: l'ora la decide l'utente. */
  readonly time: string;
  /** Solo per l'ora che a Milano capita due volte (cambio all'ora solare): quale delle due si intende. */
  readonly ambiguousChoice: AmbiguousChoice;
  /** Quali campi del luogo li ha compilati il servizio. Se il punto si sposta vanno rifatti; ciò che ha scritto l'utente non si tocca. */
  readonly autoFilled: { readonly address: boolean; readonly neighbourhood: boolean };
}

export const initialQuickCreateDraft: QuickCreateDraft = {
  values: initialEventFormValues,
  placeSource: null,
  dateChoice: null,
  customDate: "",
  time: "",
  ambiguousChoice: "first",
  autoFilled: { address: false, neighbourhood: false },
};

export type QuickErrorKey =
  | "title"
  | "category"
  | "venueName"
  | "point"
  | "address"
  | "neighbourhood"
  | "date"
  | "time"
  | "description"
  | "imageAlt";

export type QuickErrors = Partial<Record<QuickErrorKey, string>>;

/** Quali errori appartengono a quale passaggio: serve a bloccare "Continua" e a riportare l'utente dove deve correggere. */
export const QUICK_STEP_FIELDS: Readonly<Record<QuickStep, readonly QuickErrorKey[]>> = {
  1: ["title", "category"],
  2: ["venueName", "point", "address", "neighbourhood", "date", "time"],
  3: ["description", "imageAlt"],
};

/** Ordine di lettura dei campi nell'interfaccia, per mettere a fuoco il primo errore. */
export const QUICK_ERROR_ORDER: readonly QuickErrorKey[] = [
  "title", "category", "venueName", "point", "address", "neighbourhood", "date", "time", "description", "imageAlt",
];

const scheduleMessages: Record<ScheduleIssue, { readonly key: "date" | "time"; readonly message: string }> = {
  "date-missing": { key: "date", message: "Scegli quando." },
  "date-invalid": { key: "date", message: "Inserisci una data valida." },
  "time-missing": { key: "time", message: "Scegli l’ora di inizio." },
  "time-invalid": { key: "time", message: "Inserisci un’ora valida." },
  nonexistent: { key: "time", message: "A Milano quest’ora non esiste: quel giorno le lancette saltano dalle 02:00 alle 03:00. Scegli un altro orario." },
  past: { key: "time", message: "Questo orario è già passato. Scegli un orario successivo oppure un altro giorno." },
};

/** Errore di data/ora per una verifica già calcolata: lo usa anche l'interfaccia per mostrare subito un orario trascorso. */
export function scheduleError(issue: ScheduleIssue): { readonly key: "date" | "time"; readonly message: string } {
  return scheduleMessages[issue];
}

export function stepOfError(key: QuickErrorKey): QuickStep {
  if (QUICK_STEP_FIELDS[1].includes(key)) return 1;
  if (QUICK_STEP_FIELDS[2].includes(key)) return 2;
  return 3;
}

export function firstErrorKey(errors: QuickErrors): QuickErrorKey | null {
  return QUICK_ERROR_ORDER.find((key) => errors[key]) ?? null;
}

export function errorsForStep(errors: QuickErrors, step: QuickStep): QuickErrors {
  const stepErrors: QuickErrors = {};
  for (const key of QUICK_STEP_FIELDS[step]) {
    const message = errors[key];
    if (message) stepErrors[key] = message;
  }
  return stepErrors;
}

/** Data e ora risolte rispetto a `now`; `startsAt` vuoto se l'utente non le ha ancora indicate correttamente. */
export function getDraftSchedule(draft: QuickCreateDraft, now: Date) {
  return checkSchedule(draft.dateChoice, draft.customDate, draft.time, now, draft.ambiguousChoice);
}

/**
 * Il backend richiede una descrizione; in Quick Create è facoltativa. Se l'utente non scrive nulla, nel payload va il titolo
 * (che passa dalle stesse regole: 3–120 caratteri). All'utente non si mostra mai un testo inventato: la schermata dice cosa succede.
 */
export function effectiveDescription(values: EventFormValues): string {
  return values.description.trim() || values.title.trim();
}

/** I valori da inviare: la bozza più l'inizio risolto come istante (ISO UTC) a partire dall'ora di Milano, e la descrizione effettiva. */
export function toFormValues(draft: QuickCreateDraft, now: Date): EventFormValues {
  return { ...draft.values, description: effectiveDescription(draft.values), startsAt: getDraftSchedule(draft, now).startsAt };
}

export function validateQuickCreate(
  draft: QuickCreateDraft,
  now: Date,
  hasSelectedImage: boolean,
  mapAvailable: boolean,
): QuickErrors {
  const schedule = getDraftSchedule(draft, now);
  const form = validateEventForm(
    { ...draft.values, description: effectiveDescription(draft.values), startsAt: schedule.startsAt },
    undefined,
    hasSelectedImage,
  );
  const errors: QuickErrors = {};

  if (form.title) errors.title = form.title;
  if (form.category) errors.category = form.category;
  if (form.venueName) errors.venueName = form.venueName;
  if (form.address) errors.address = form.address;
  if (form.neighbourhood) errors.neighbourhood = form.neighbourhood;
  if (form.description) errors.description = form.description;
  if (form.imageAlt) errors.imageAlt = form.imageAlt;

  if (form.latitude || form.longitude) {
    const latitude = parseCoordinate(draft.values.latitude);
    const longitude = parseCoordinate(draft.values.longitude);
    if (latitude === null || longitude === null) {
      errors.point = mapAvailable
        ? "Scegli il luogo tra i suggerimenti oppure indica il punto sulla mappa."
        : "Scegli il luogo tra i suggerimenti. Se la ricerca non funziona, usa «Crea evento completo».";
    } else {
      errors.point = "Il punto scelto è fuori Milano: LINKR mostra solo eventi in città.";
    }
  }

  if (schedule.issue) {
    const { key, message } = scheduleMessages[schedule.issue];
    errors[key] = message;
  }

  return errors;
}

/** Errori di campo del backend (400) riportati sui campi di Quick Create. `imageUrl`, `endsAt`, `eventType` non sono campi qui. */
export function quickErrorsFromServer(serverErrors: EventFormErrors): QuickErrors {
  const errors: QuickErrors = {};
  if (serverErrors.title) errors.title = serverErrors.title;
  if (serverErrors.category) errors.category = serverErrors.category;
  if (serverErrors.venueName) errors.venueName = serverErrors.venueName;
  if (serverErrors.address) errors.address = serverErrors.address;
  if (serverErrors.neighbourhood) errors.neighbourhood = serverErrors.neighbourhood;
  if (serverErrors.latitude || serverErrors.longitude) {
    errors.point = "Il punto scelto non è valido: scegli un luogo a Milano.";
  }
  if (serverErrors.startsAt) errors.time = serverErrors.startsAt;
  if (serverErrors.description) errors.description = serverErrors.description;
  if (serverErrors.imageAlt) errors.imageAlt = serverErrors.imageAlt;
  return errors;
}

/** `true` se c'è qualcosa che l'utente perderebbe chiudendo: serve alla conferma prima di scartare. */
export function hasQuickCreateProgress(draft: QuickCreateDraft, hasImage: boolean): boolean {
  const { values } = draft;
  return Boolean(
    values.title.trim()
    || values.category
    || values.venueName.trim()
    || values.latitude
    || draft.dateChoice
    || draft.time
    || values.description.trim()
    || hasImage,
  );
}

export function draftPoint(draft: QuickCreateDraft): { readonly latitude: number; readonly longitude: number } | null {
  const latitude = parseCoordinate(draft.values.latitude);
  const longitude = parseCoordinate(draft.values.longitude);
  return latitude === null || longitude === null ? null : { latitude, longitude };
}

export function hasDraftPoint(draft: QuickCreateDraft): boolean {
  const point = draftPoint(draft);
  return point !== null && isInsideMilan(point.latitude, point.longitude);
}

/** Selezione di un suggerimento: sostituisce tutto il luogo. I dati del luogo precedente non devono sopravvivere (indirizzo e quartiere compresi). */
export function withSuggestion(draft: QuickCreateDraft, suggestion: PlaceSuggestion): QuickCreateDraft {
  return {
    ...draft,
    placeSource: "search",
    values: {
      ...draft.values,
      venueName: suggestion.venueName,
      address: suggestion.address ?? "",
      neighbourhood: suggestion.neighbourhood ?? "",
      latitude: suggestion.latitude.toFixed(6),
      longitude: suggestion.longitude.toFixed(6),
    },
    autoFilled: { address: suggestion.address !== undefined, neighbourhood: suggestion.neighbourhood !== undefined },
  };
}

/** Indirizzo e quartiere recuperati in automatico per il punto scelto: riempiono solo ciò che manca (vedi `fillPlaceDetails`). */
export function withResolvedPlace(
  draft: QuickCreateDraft,
  point: { readonly latitude: number; readonly longitude: number },
  completed: CompletedPlace,
): QuickCreateDraft {
  const values = fillPlaceDetails(draft.values, point, completed);
  if (values === draft.values) return draft;
  return {
    ...draft,
    values,
    autoFilled: {
      address: draft.autoFilled.address || values.address !== draft.values.address,
      neighbourhood: draft.autoFilled.neighbourhood || values.neighbourhood !== draft.values.neighbourhood,
    },
  };
}

/** Indirizzo o quartiere scritti a mano: da qui in poi sono dell'utente e un nuovo punto sulla mappa non li cancella. */
export function withPlaceDetail(draft: QuickCreateDraft, field: "address" | "neighbourhood", value: string): QuickCreateDraft {
  return { ...draft, values: { ...draft.values, [field]: value }, autoFilled: { ...draft.autoFilled, [field]: false } };
}

/**
 * Digitazione nel campo luogo. Se il luogo veniva da un suggerimento, cambiare il nome lo rende un'altra cosa:
 * coordinate, indirizzo e quartiere non sono più affidabili e vanno riscelti. Un punto indicato sulla mappa invece resta,
 * perché il nome lo scrive l'utente.
 */
export function withVenueName(draft: QuickCreateDraft, venueName: string): QuickCreateDraft {
  if (draft.placeSource === "search") {
    return {
      ...draft,
      placeSource: null,
      values: { ...draft.values, venueName, address: "", neighbourhood: "", latitude: "", longitude: "" },
      autoFilled: { address: false, neighbourhood: false },
    };
  }
  return { ...draft, values: { ...draft.values, venueName } };
}

/**
 * Punto scelto sulla mappa. Se prima c'era un luogo da suggerimento, nome/indirizzo/quartiere non descrivono più
 * il nuovo punto e vengono tolti; quello che l'utente aveva scritto a mano per un punto sulla mappa si conserva.
 */
export function withMapPoint(draft: QuickCreateDraft, latitude: number, longitude: number): QuickCreateDraft {
  const fromSearch = draft.placeSource === "search";
  // Spostare il punto cambia la via: indirizzo e quartiere trovati dal servizio per il punto di prima non valgono più.
  const clearAddress = fromSearch || draft.autoFilled.address;
  const clearNeighbourhood = fromSearch || draft.autoFilled.neighbourhood;
  return {
    ...draft,
    placeSource: "map",
    values: {
      ...draft.values,
      ...(fromSearch ? { venueName: "" } : {}),
      ...(clearAddress ? { address: "" } : {}),
      ...(clearNeighbourhood ? { neighbourhood: "" } : {}),
      latitude: latitude.toFixed(6),
      longitude: longitude.toFixed(6),
    },
    autoFilled: {
      address: clearAddress ? false : draft.autoFilled.address,
      neighbourhood: clearNeighbourhood ? false : draft.autoFilled.neighbourhood,
    },
  };
}
