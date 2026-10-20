import { ArrowLeft, BadgeCheck, CalendarDays, Image, LoaderCircle, MapPin, RotateCcw, Users } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";

import { FormField } from "../components/auth/FormField";
import { EventLocationPicker } from "../components/events/EventLocationPicker";
import { useAuth } from "../context/AuthContext";
import { categoryOptions } from "../lib/eventPresentation";
import {
  createEvent,
  getCreateEventFailure,
  getUpdateEventFailure,
  updateEvent,
  type CreateEventPayload,
} from "../lib/eventCreation";
import { getEvent, getEventsErrorMessage, isRequestCancelled } from "../lib/events";
import type { EventCategory, EventDetail, EventType } from "../types/event";

interface EventFormValues {
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

type EventFormErrors = Partial<Record<keyof EventFormValues, string>>;

const initialValues: EventFormValues = {
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

const EVENT_DRAFT_KEY_PREFIX = "linkr:create-event-draft:";
const MAX_EVENT_DURATION_MS = 7 * 24 * 60 * 60 * 1_000;

const backendFieldMessages: Partial<Record<keyof EventFormValues, string>> = {
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

function readDraft(key: string): EventFormValues {
  try {
    const rawDraft = window.sessionStorage.getItem(key);
    if (!rawDraft) return initialValues;
    const parsed = JSON.parse(rawDraft) as Record<string, unknown>;
    const entries = Object.entries(initialValues).map(([field, fallback]) => [
      field,
      typeof parsed[field] === "string" ? parsed[field] : fallback,
    ]);
    const draft = Object.fromEntries(entries) as unknown as EventFormValues;
    return {
      ...draft,
      eventType: draft.eventType === "OFFICIAL" ? "OFFICIAL" : "COMMUNITY",
    };
  } catch {
    return initialValues;
  }
}

function storeDraft(key: string, values: EventFormValues) {
  try {
    window.sessionStorage.setItem(key, JSON.stringify(values));
  } catch {
    // Il form continua a funzionare anche quando lo storage del browser non e' disponibile.
  }
}

function removeDraft(key: string) {
  try {
    window.sessionStorage.removeItem(key);
  } catch {
    // Nessuna azione necessaria: il salvataggio sul backend e' gia' l'operazione autorevole.
  }
}

function parseCoordinate(value: string): number | null {
  if (!value.trim()) return null;
  const coordinate = Number(value);
  return Number.isFinite(coordinate) ? coordinate : null;
}

function isValidImageUrl(value: string): boolean {
  return /^https:\/\/\S+$/.test(value);
}

function validate(values: EventFormValues, originalStartsAt?: string): EventFormErrors {
  const errors: EventFormErrors = {};
  const title = values.title.trim();
  const description = values.description.trim();
  const venueName = values.venueName.trim();
  const address = values.address.trim();
  const neighbourhood = values.neighbourhood.trim();
  const latitude = parseCoordinate(values.latitude);
  const longitude = parseCoordinate(values.longitude);
  const startsAt = values.startsAt ? new Date(values.startsAt).getTime() : Number.NaN;
  const endsAt = values.endsAt ? new Date(values.endsAt).getTime() : null;

  if (!title) errors.title = "Inserisci un titolo.";
  else if (title.length < 3) errors.title = "Il titolo deve contenere almeno 3 caratteri.";
  else if (title.length > 120) errors.title = "Il titolo non può superare 120 caratteri.";

  if (!description) errors.description = "Descrivi cosa succederà durante l’evento.";
  else if (description.length > 5_000) errors.description = "La descrizione non può superare 5.000 caratteri.";

  if (!values.category) errors.category = "Scegli una categoria.";

  if (!values.startsAt) errors.startsAt = "Scegli data e ora di inizio.";
  else if (!Number.isFinite(startsAt)) errors.startsAt = "Inserisci una data e un’ora valide.";
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
  else if (latitude < 45.35 || latitude > 45.60) errors.latitude = "La latitudine deve essere tra 45.35 e 45.60.";

  if (longitude === null) errors.longitude = "Inserisci una longitudine valida.";
  else if (longitude < 8.95 || longitude > 9.45) errors.longitude = "La longitudine deve essere tra 8.95 e 9.45.";

  const imageUrl = values.imageUrl.trim();
  if (imageUrl.length > 500) errors.imageUrl = "L’URL dell’immagine non può superare 500 caratteri.";
  else if (imageUrl && !isValidImageUrl(imageUrl)) errors.imageUrl = "Inserisci un URL HTTPS senza spazi.";

  const imageAlt = values.imageAlt.trim();
  if (imageAlt.length > 200) errors.imageAlt = "Il testo alternativo non può superare 200 caratteri.";
  else if (imageUrl && !imageAlt) errors.imageAlt = "Descrivi l’immagine per chi non può vederla.";

  if (values.imageCredit.trim().length > 120) errors.imageCredit = "I crediti non possono superare 120 caratteri.";

  return errors;
}

function toLocalDateTime(isoDate: string | null): string {
  if (!isoDate) return "";
  const date = new Date(isoDate);
  if (Number.isNaN(date.getTime())) return "";
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function valuesFromEvent(event: EventDetail): EventFormValues {
  return {
    title: event.title,
    description: event.description,
    category: event.category,
    eventType: event.type,
    startsAt: toLocalDateTime(event.startsAt),
    endsAt: toLocalDateTime(event.endsAt),
    venueName: event.venueName,
    address: event.address,
    neighbourhood: event.neighbourhood,
    latitude: String(event.latitude),
    longitude: String(event.longitude),
    imageUrl: event.imageUrl ?? "",
    imageAlt: event.imageAlt ?? "",
    imageCredit: event.imageCredit ?? "",
  };
}

function EventFormPage({ mode }: { readonly mode: "create" | "edit" }) {
  const { authorizedRequest, user } = useAuth();
  const navigate = useNavigate();
  const { eventId } = useParams<{ eventId: string }>();
  const formRef = useRef<HTMLFormElement>(null);
  const submittingRef = useRef(false);
  const isAdmin = user?.role === "ADMIN";
  const isEdit = mode === "edit";
  const draftKey = `${EVENT_DRAFT_KEY_PREFIX}${user?.id ?? "unknown"}`;
  const [values, setValues] = useState<EventFormValues>(() => {
    const draft = isEdit ? initialValues : readDraft(draftKey);
    return isAdmin ? draft : { ...draft, eventType: "COMMUNITY" };
  });
  const [errors, setErrors] = useState<EventFormErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [loading, setLoading] = useState(isEdit);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [requestKey, setRequestKey] = useState(0);
  const [originalStartsAt, setOriginalStartsAt] = useState<string>();

  useEffect(() => {
    if (!isEdit) return undefined;
    if (!eventId) {
      setLoadError("Identificativo evento non valido.");
      setLoading(false);
      return undefined;
    }
    const controller = new AbortController();
    setLoading(true);
    setLoadError(null);
    void authorizedRequest((accessToken) => getEvent(eventId, accessToken, controller.signal))
      .then((event) => {
        if (!event.canEdit) {
          setLoadError("Questo evento è terminato, annullato oppure non può essere modificato dal tuo account.");
          return;
        }
        setValues(valuesFromEvent(event));
        setOriginalStartsAt(event.startsAt);
      })
      .catch((error: unknown) => {
        if (!isRequestCancelled(error)) setLoadError(getEventsErrorMessage(error));
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [authorizedRequest, eventId, isEdit, requestKey]);

  function updateValue<Field extends keyof EventFormValues>(field: Field, value: EventFormValues[Field]) {
    setValues((current) => {
      const next = { ...current, [field]: value };
      if (!isEdit) storeDraft(draftKey, next);
      return next;
    });
    setErrors((current) => {
      if (!current[field]) return current;
      const next = { ...current };
      delete next[field];
      return next;
    });
    setFormError(null);
  }

  function updateCoordinates(latitude: number, longitude: number) {
    updateValue("latitude", latitude.toFixed(6));
    updateValue("longitude", longitude.toFixed(6));
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submittingRef.current) return;

    const validationErrors = validate(values, originalStartsAt);
    setErrors(validationErrors);
    setFormError(null);
    if (Object.keys(validationErrors).length > 0) {
      window.requestAnimationFrame(() => {
        formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
      });
      return;
    }

    const payload: CreateEventPayload = {
      title: values.title.trim(),
      description: values.description.trim(),
      category: values.category as EventCategory,
      startsAt: new Date(values.startsAt).toISOString(),
      ...(values.endsAt ? { endsAt: new Date(values.endsAt).toISOString() } : {}),
      venueName: values.venueName.trim(),
      address: values.address.trim(),
      neighbourhood: values.neighbourhood.trim(),
      latitude: Number(values.latitude),
      longitude: Number(values.longitude),
      ...(values.imageUrl.trim() ? { imageUrl: values.imageUrl.trim() } : {}),
      ...(values.imageAlt.trim() ? { imageAlt: values.imageAlt.trim() } : {}),
      ...(values.imageCredit.trim() ? { imageCredit: values.imageCredit.trim() } : {}),
      type: isAdmin ? values.eventType : "COMMUNITY",
    };

    submittingRef.current = true;
    setSubmitting(true);
    try {
      const savedEvent = await authorizedRequest((accessToken) => (
        isEdit && eventId
          ? updateEvent(accessToken, eventId, payload)
          : createEvent(accessToken, payload)
      ));
      if (!savedEvent.id) throw new Error("Event response is missing the event id");
      if (!isEdit) removeDraft(draftKey);
      navigate(`/events/${encodeURIComponent(savedEvent.id)}`, { replace: true });
    } catch (error) {
      const failure = isEdit ? getUpdateEventFailure(error) : getCreateEventFailure(error);
      const serverErrors: EventFormErrors = {};
      for (const field of Object.keys(failure.fieldErrors ?? {})) {
        const formField = field === "type" ? "eventType" : field;
        if (formField in initialValues) {
          const typedField = formField as keyof EventFormValues;
          serverErrors[typedField] = backendFieldMessages[typedField] ?? "Controlla questo campo.";
        }
      }
      if (Object.keys(serverErrors).length > 0) setErrors((current) => ({ ...current, ...serverErrors }));
      setFormError(failure.message);
      window.requestAnimationFrame(() => formRef.current?.querySelector<HTMLElement>('[role="alert"]')?.focus());
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  const latitude = parseCoordinate(values.latitude);
  const longitude = parseCoordinate(values.longitude);
  const returnTo = isEdit && eventId ? `/events/${encodeURIComponent(eventId)}` : "/";

  if (loading) {
    return <div className="page-shell page-with-mobile-nav py-20 text-center font-bold text-muted" aria-busy="true">Caricamento evento…</div>;
  }

  if (loadError) {
    return (
      <div className="page-shell page-with-mobile-nav py-16">
        <div className="mx-auto max-w-xl rounded-[1.75rem] border border-line bg-surface px-6 py-12 text-center">
          <h1 className="text-3xl font-extrabold tracking-[-0.045em] text-ink">Modifica non disponibile</h1>
          <p className="mt-3 text-sm leading-6 text-muted">{loadError}</p>
          <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:justify-center">
            <Link className="button-secondary inline-flex items-center" to={returnTo}>Torna all’evento</Link>
            <button className="button-primary inline-flex items-center gap-2" type="button" onClick={() => setRequestKey((current) => current + 1)}>
              <RotateCcw size={17} aria-hidden="true" /> Riprova
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="page-shell page-with-mobile-nav pb-16 pt-8 sm:pt-12 lg:pt-16">
      <Link className="inline-flex min-h-11 items-center gap-2 rounded-full font-extrabold text-ink" to={returnTo}>
        <ArrowLeft size={19} aria-hidden="true" /> {isEdit ? "Torna all’evento" : "Torna a Discover"}
      </Link>

      <header className="mt-5 max-w-3xl">
        <p className="section-kicker">{isEdit ? "Gestisci evento" : "Crea su LINKR"}</p>
        <h1 className="mt-3 text-[clamp(2.6rem,8vw,5.5rem)] font-extrabold leading-[0.94] tracking-[-0.06em] text-ink">
          {isEdit ? "Aggiorna il tuo evento." : "Porta le persone fuori, insieme."}
        </h1>
        <p className="mt-5 max-w-2xl text-base leading-7 text-muted sm:text-lg">
          {isEdit
            ? "Modifica le informazioni e salva: posizione, dettagli e immagine verranno aggiornati insieme."
            : isAdmin
            ? "Organizza un’uscita della community o pubblica un appuntamento ufficiale. Inserisci informazioni reali e scegli il punto esatto sulla mappa."
            : "Organizza un’uscita della community. Inserisci informazioni reali e scegli il punto esatto sulla mappa."}
        </p>
      </header>

      <form ref={formRef} className="mt-8" onSubmit={handleSubmit} noValidate aria-busy={submitting}>
        {formError ? (
          <div className="form-message form-message--error mb-5" role="alert" tabIndex={-1}>{formError}</div>
        ) : null}

        <div className="create-event-layout">
          <div className="space-y-5">
            <section className="create-event-card" aria-labelledby="event-details-title">
              <div className="flex items-start gap-3">
                <span className="create-event-card__icon"><CalendarDays size={20} aria-hidden="true" /></span>
                <div>
                  <p className="section-kicker">Dettagli</p>
                  <h2 id="event-details-title" className="mt-1 text-2xl font-extrabold tracking-[-0.04em] text-ink">Racconta l’evento</h2>
                </div>
              </div>

              <div className="mt-6 space-y-5">
                <FormField
                  id="title"
                  label="Titolo"
                  value={values.title}
                  onChange={(event) => updateValue("title", event.target.value)}
                  error={errors.title}
                  maxLength={120}
                  placeholder="Es. Aperitivo creativo sui Navigli"
                  disabled={submitting}
                />

                <div>
                  <label className="form-label" htmlFor="description">Descrizione</label>
                  <textarea
                    id="description"
                    name="description"
                    className="form-input form-textarea mt-2"
                    value={values.description}
                    onChange={(event) => updateValue("description", event.target.value)}
                    aria-invalid={errors.description ? true : undefined}
                    aria-describedby={errors.description ? "description-error" : "description-hint"}
                    maxLength={5_000}
                    placeholder="Cosa farete, per chi è pensato e cosa bisogna sapere."
                    disabled={submitting}
                  />
                  {errors.description
                    ? <p id="description-error" className="field-error mt-2">{errors.description}</p>
                    : <p id="description-hint" className="mt-2 text-xs leading-5 text-muted">Informazioni chiare aiutano le persone a decidere se partecipare.</p>}
                </div>

                <div className="grid gap-5 sm:grid-cols-2">
                  <div>
                    <label className="form-label" htmlFor="category">Categoria</label>
                    <select
                      id="category"
                      name="category"
                      className="form-input mt-2"
                      value={values.category}
                      onChange={(event) => updateValue("category", event.target.value as EventCategory | "")}
                      aria-invalid={errors.category ? true : undefined}
                      aria-describedby={errors.category ? "category-error" : undefined}
                      disabled={submitting}
                    >
                      <option value="">Seleziona</option>
                      {categoryOptions.map((option) => option.value
                        ? <option key={option.value} value={option.value}>{option.label}</option>
                        : null)}
                    </select>
                    {errors.category ? <p id="category-error" className="field-error mt-2">{errors.category}</p> : null}
                  </div>

                  <FormField
                    id="startsAt"
                    label="Inizio"
                    type="datetime-local"
                    value={values.startsAt}
                    onChange={(event) => updateValue("startsAt", event.target.value)}
                    error={errors.startsAt}
                    disabled={submitting}
                  />
                </div>

                <FormField
                  id="endsAt"
                  label="Fine (opzionale)"
                  type="datetime-local"
                  value={values.endsAt}
                  onChange={(event) => updateValue("endsAt", event.target.value)}
                  error={errors.endsAt}
                  hint="Può essere distante al massimo 7 giorni dall’inizio."
                  disabled={submitting}
                />
              </div>
            </section>

            <section className="create-event-card" aria-labelledby="event-type-title">
              <div className="flex items-start gap-3">
                <span className="create-event-card__icon"><Users size={20} aria-hidden="true" /></span>
                <div>
                  <p className="section-kicker">Formato</p>
                  <h2 id="event-type-title" className="mt-1 text-2xl font-extrabold tracking-[-0.04em] text-ink">Che tipo di evento è?</h2>
                </div>
              </div>

              <fieldset className={`mt-6 grid gap-3 ${isAdmin ? "sm:grid-cols-2" : ""}`} disabled={submitting}>
                <legend className="sr-only">Tipo di evento</legend>
                <label className="event-type-option" data-selected={values.eventType === "COMMUNITY"}>
                  <input
                    className="sr-only"
                    type="radio"
                    name="eventType"
                    value="COMMUNITY"
                    checked={values.eventType === "COMMUNITY"}
                    onChange={() => updateValue("eventType", "COMMUNITY")}
                  />
                  <Users size={20} aria-hidden="true" />
                  <span>
                    <span className="block font-extrabold">Community</span>
                    <span className="mt-1 block text-xs font-semibold leading-5 opacity-75">Un’uscita organizzata da te.</span>
                  </span>
                </label>
                {isAdmin ? (
                  <label className="event-type-option" data-selected={values.eventType === "OFFICIAL"}>
                    <input
                      className="sr-only"
                      type="radio"
                      name="eventType"
                      value="OFFICIAL"
                      checked={values.eventType === "OFFICIAL"}
                      onChange={() => updateValue("eventType", "OFFICIAL")}
                    />
                    <BadgeCheck size={20} aria-hidden="true" />
                    <span>
                      <span className="block font-extrabold">Official</span>
                      <span className="mt-1 block text-xs font-semibold leading-5 opacity-75">Disponibile perché il tuo account è amministratore.</span>
                    </span>
                  </label>
                ) : null}
              </fieldset>
            </section>

            <section className="create-event-card" aria-labelledby="event-image-title">
              <div className="flex items-start gap-3">
                <span className="create-event-card__icon"><Image size={20} aria-hidden="true" /></span>
                <div>
                  <p className="section-kicker">Immagine</p>
                  <h2 id="event-image-title" className="mt-1 text-2xl font-extrabold tracking-[-0.04em] text-ink">Aggiungi una copertina</h2>
                </div>
              </div>
              <div className="mt-6 space-y-5">
                <FormField
                  id="imageUrl"
                  label="URL immagine (opzionale)"
                  type="url"
                  value={values.imageUrl}
                  onChange={(event) => updateValue("imageUrl", event.target.value)}
                  error={errors.imageUrl}
                  hint="Usa un indirizzo pubblico HTTPS senza spazi. Puoi lasciare il campo vuoto."
                  maxLength={500}
                  placeholder="https://…"
                  disabled={submitting}
                />
                <FormField
                  id="imageAlt"
                  label={`Testo alternativo${values.imageUrl.trim() ? "" : " (opzionale)"}`}
                  value={values.imageAlt}
                  onChange={(event) => updateValue("imageAlt", event.target.value)}
                  error={errors.imageAlt}
                  hint="Obbligatorio quando aggiungi un’immagine. Descrivila in modo conciso."
                  maxLength={200}
                  placeholder="Es. Persone sedute a un tavolo all’aperto"
                  disabled={submitting}
                />
                <FormField
                  id="imageCredit"
                  label="Crediti immagine (opzionale)"
                  value={values.imageCredit}
                  onChange={(event) => updateValue("imageCredit", event.target.value)}
                  error={errors.imageCredit}
                  maxLength={120}
                  placeholder="Autore o fonte"
                  disabled={submitting}
                />
              </div>
            </section>
          </div>

          <aside className="create-event-location" aria-labelledby="event-location-title">
            <div className="flex items-start gap-3">
              <span className="create-event-card__icon"><MapPin size={20} aria-hidden="true" /></span>
              <div>
                <p className="section-kicker">Luogo</p>
                <h2 id="event-location-title" className="mt-1 text-2xl font-extrabold tracking-[-0.04em] text-ink">Dove vi trovate?</h2>
              </div>
            </div>

            <div className="mt-6">
              <FormField
                id="venueName"
                label="Nome del luogo"
                value={values.venueName}
                onChange={(event) => updateValue("venueName", event.target.value)}
                error={errors.venueName}
                maxLength={120}
                placeholder="Es. Giardini Indro Montanelli"
                disabled={submitting}
              />
            </div>

            <div className="mt-5 grid gap-5 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)] lg:grid-cols-1 xl:grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)]">
              <FormField
                id="address"
                label="Indirizzo"
                value={values.address}
                onChange={(event) => updateValue("address", event.target.value)}
                error={errors.address}
                maxLength={200}
                placeholder="Via e numero civico"
                disabled={submitting}
              />
              <FormField
                id="neighbourhood"
                label="Quartiere"
                value={values.neighbourhood}
                onChange={(event) => updateValue("neighbourhood", event.target.value)}
                error={errors.neighbourhood}
                maxLength={60}
                placeholder="Es. Isola"
                disabled={submitting}
              />
            </div>

            <p className="mt-5 text-sm font-semibold leading-6 text-muted">Tocca la mappa o trascina il segnaposto per scegliere il punto esatto.</p>
            <div className="mt-3 overflow-hidden rounded-[1.25rem] border border-line">
              <EventLocationPicker
                latitude={latitude}
                longitude={longitude}
                disabled={submitting}
                onChange={updateCoordinates}
              />
            </div>

            <div className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
              <FormField
                id="latitude"
                label="Latitudine"
                type="number"
                inputMode="decimal"
                min={45.35}
                max={45.60}
                step="any"
                value={values.latitude}
                onChange={(event) => updateValue("latitude", event.target.value)}
                error={errors.latitude}
                placeholder="45.464200"
                disabled={submitting}
              />
              <FormField
                id="longitude"
                label="Longitudine"
                type="number"
                inputMode="decimal"
                min={8.95}
                max={9.45}
                step="any"
                value={values.longitude}
                onChange={(event) => updateValue("longitude", event.target.value)}
                error={errors.longitude}
                placeholder="9.190000"
                disabled={submitting}
              />
            </div>
          </aside>
        </div>

        <div className="create-event-actions">
          <p className="text-xs font-semibold leading-5 text-muted">{isEdit ? "Il dettaglio verrà aggiornato dopo il salvataggio." : "L’evento verrà inviato a LINKR quando premi Pubblica."}</p>
          <div className="mt-3 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
            <Link
              className="button-secondary inline-flex items-center"
              to={returnTo}
              aria-disabled={submitting}
              tabIndex={submitting ? -1 : undefined}
              onClick={(event) => {
                if (submitting) event.preventDefault();
                else if (!isEdit) removeDraft(draftKey);
              }}
            >
              Annulla
            </Link>
            <button className="button-primary inline-flex items-center gap-2" type="submit" disabled={submitting} aria-busy={submitting}>
              {submitting ? <LoaderCircle className="animate-spin motion-reduce:animate-none" size={18} aria-hidden="true" /> : null}
              {submitting ? (isEdit ? "Salvataggio…" : "Pubblicazione…") : (isEdit ? "Salva modifiche" : "Pubblica evento")}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}

export function CreateEventPage() {
  return <EventFormPage mode="create" />;
}

export function EditEventPage() {
  return <EventFormPage mode="edit" />;
}
