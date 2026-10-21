import { ArrowLeft, BadgeCheck, CalendarDays, Image, LoaderCircle, MapPin, RotateCcw, Users } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";

import { FormField } from "../components/auth/FormField";
import { EventCoverField } from "../components/events/EventCoverField";
import { EventLocationPicker } from "../components/events/EventLocationPicker";
import { PlaceAutocomplete } from "../components/events/PlaceAutocomplete";
import { useAuth } from "../context/AuthContext";
import { useEventCover } from "../hooks/useEventCover";
import { usePlaceResolution } from "../hooks/usePlaceResolution";
import { categoryOptions } from "../lib/eventPresentation";
import { uploadEventImage } from "../lib/eventImages";
import type { PlaceSuggestion } from "../lib/geocoding";
import {
  createEvent,
  getCreateEventFailure,
  getUpdateEventFailure,
  updateEvent,
} from "../lib/eventCreation";
import {
  buildEventPayload,
  fillPlaceDetails,
  initialEventFormValues as initialValues,
  mapServerFieldErrors,
  parseCoordinate,
  validateEventForm,
} from "../lib/eventForm";
import type { EventFormErrors, EventFormValues } from "../lib/eventForm";
import { getEvent, getEventsErrorMessage, isRequestCancelled } from "../lib/events";
import { toRomeLocalInput } from "../lib/romeTime";
import type { EventCategory, EventDetail } from "../types/event";

const EVENT_DRAFT_KEY_PREFIX = "linkr:create-event-draft:";

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

function valuesFromEvent(event: EventDetail): EventFormValues {
  return {
    title: event.title,
    description: event.description,
    category: event.category,
    eventType: event.type,
    // Il campo mostra e legge l'ora di Milano, non quella del dispositivo (vedi `romeTime.ts`).
    startsAt: toRomeLocalInput(event.startsAt),
    endsAt: toRomeLocalInput(event.endsAt),
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
  const [mapFocusRevision, setMapFocusRevision] = useState(0);
  const cover = useEventCover({
    onUrlChange: (value) => updateValue("imageUrl", value),
    onFileAccepted: () => {
      setErrors((current) => {
        if (!current.imageUrl) return current;
        const next = { ...current };
        delete next.imageUrl;
        return next;
      });
      setFormError(null);
    },
  });
  const {
    selectedImage,
    fileError: imageFileError,
    fileRejected: imageFileRejected,
    statusMessage: imageStatusMessage,
    uploading: uploadingImage,
    validating: validatingImage,
  } = cover;

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

  // Scelto il punto una volta (suggerimento o mappa), indirizzo e quartiere mancanti si recuperano da soli e riempiono solo i campi vuoti.
  const placeResolution = usePlaceResolution((point, completed) => {
    setValues((current) => {
      const next = fillPlaceDetails(current, point, completed);
      if (next !== current && !isEdit) storeDraft(draftKey, next);
      return next;
    });
    setErrors((current) => {
      if (!current.address && !current.neighbourhood) return current;
      const next = { ...current };
      if (completed.address) delete next.address;
      if (completed.neighbourhood) delete next.neighbourhood;
      return next;
    });
  });

  function updateCoordinates(latitude: number, longitude: number) {
    updateValue("latitude", latitude.toFixed(6));
    updateValue("longitude", longitude.toFixed(6));
    void placeResolution.resolve({ latitude, longitude, place: { venueName: "", landmark: false }, origin: "map" });
  }

  function selectPlace(suggestion: PlaceSuggestion) {
    setValues((current) => {
      const next: EventFormValues = {
        ...current,
        venueName: suggestion.venueName,
        // Un nuovo luogo sostituisce quello di prima, indirizzo e quartiere compresi: se il servizio non li ha, si cercano o si chiedono.
        address: suggestion.address ?? "",
        neighbourhood: suggestion.neighbourhood ?? "",
        latitude: suggestion.latitude.toFixed(6),
        longitude: suggestion.longitude.toFixed(6),
      };
      if (!isEdit) storeDraft(draftKey, next);
      return next;
    });
    setErrors((current) => {
      const next = { ...current };
      delete next.venueName;
      delete next.latitude;
      delete next.longitude;
      if (suggestion.address) delete next.address;
      if (suggestion.neighbourhood) delete next.neighbourhood;
      return next;
    });
    setFormError(null);
    setMapFocusRevision((current) => current + 1);
    void placeResolution.resolve({ latitude: suggestion.latitude, longitude: suggestion.longitude, place: suggestion, origin: "search" });
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submittingRef.current || validatingImage) return;
    // Solo un file scartato blocca l'invio: dopo un upload fallito la foto resta selezionata e si può ritentare (come in Quick Create).
    if (imageFileRejected) {
      window.requestAnimationFrame(() => formRef.current?.querySelector<HTMLElement>("#event-cover-file-error")?.focus());
      return;
    }

    const validationErrors = validateEventForm(values, originalStartsAt, Boolean(selectedImage));
    setErrors(validationErrors);
    setFormError(null);
    if (Object.keys(validationErrors).length > 0) {
      window.requestAnimationFrame(() => {
        formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
      });
      return;
    }

    submittingRef.current = true;
    setSubmitting(true);
    try {
      let imageUrl = values.imageUrl.trim();
      if (selectedImage) {
        const uploadedUrl = await cover.uploadSelected((file) => authorizedRequest((accessToken) => uploadEventImage(accessToken, file)));
        if (uploadedUrl === null) {
          window.requestAnimationFrame(() => formRef.current?.querySelector<HTMLElement>("#event-cover-file-error")?.focus());
          return;
        }
        imageUrl = uploadedUrl;
      }

      const payload = buildEventPayload(values, imageUrl, isAdmin ? values.eventType : "COMMUNITY");

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
      const serverErrors = mapServerFieldErrors(failure.fieldErrors);
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
        <div className="surface-card mx-auto max-w-xl px-6 py-12 text-center">
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
        <h1 className="mt-3 text-[clamp(2.25rem,6vw,4rem)] font-extrabold leading-[0.96] tracking-[-0.055em] text-ink">
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

                <div className="grid gap-5 sm:grid-cols-2">
                  <FormField
                    id="startsAt"
                    label="Inizio (ora di Milano)"
                    type="datetime-local"
                    value={values.startsAt}
                    onChange={(event) => updateValue("startsAt", event.target.value)}
                    error={errors.startsAt}
                    hint="Sempre l’ora di Milano, anche se il tuo dispositivo è in un altro fuso."
                    disabled={submitting}
                  />
                  <FormField
                    id="endsAt"
                    label="Fine (opzionale, ora di Milano)"
                    type="datetime-local"
                    value={values.endsAt}
                    onChange={(event) => updateValue("endsAt", event.target.value)}
                    error={errors.endsAt}
                    hint="Al massimo 7 giorni dopo l’inizio."
                    disabled={submitting}
                  />
                </div>
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
                <EventCoverField
                  imageUrl={values.imageUrl}
                  imageAlt={values.imageAlt}
                  selectedFile={selectedImage}
                  fileError={imageFileError}
                  urlError={errors.imageUrl}
                  statusMessage={imageStatusMessage}
                  disabled={submitting || validatingImage}
                  onFileSelect={cover.selectFile}
                  onRemove={cover.removeImage}
                  onUrlChange={cover.changeUrl}
                />
                <FormField
                  id="imageAlt"
                  label={`Testo alternativo${values.imageUrl.trim() || selectedImage ? "" : " (opzionale)"}`}
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
              <PlaceAutocomplete
                id="venueName"
                label="Nome del luogo"
                value={values.venueName}
                onChange={(value) => updateValue("venueName", value)}
                onSelect={selectPlace}
                error={errors.venueName}
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

            <p className="mt-3 min-h-5 text-xs font-bold leading-5 text-muted" role="status" aria-live="polite">
              {placeResolution.status === "resolving" ? "Cerco indirizzo e quartiere…" : null}
              {placeResolution.status === "failed" ? (
                <span>
                  {placeResolution.error} Compila tu i campi mancanti.
                  <button className="ml-2 inline-flex items-center gap-1 font-extrabold text-ink" type="button" onClick={placeResolution.retry}>
                    <RotateCcw size={13} aria-hidden="true" /> Riprova
                  </button>
                </span>
              ) : null}
            </p>

            <p className="mt-3 text-sm font-semibold leading-6 text-muted">Tocca la mappa o trascina il segnaposto per scegliere il punto esatto.</p>
            <div className="mt-3 overflow-hidden rounded-[1.25rem] border border-line">
              <EventLocationPicker
                latitude={latitude}
                longitude={longitude}
                focusRevision={mapFocusRevision}
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

        <div className="create-event-actions glass glass--strong">
          <p className="create-event-actions__hint">{isEdit ? "Il dettaglio verrà aggiornato dopo il salvataggio." : "L’evento verrà inviato a LINKR quando premi Pubblica."}</p>
          <div className="create-event-actions__buttons">
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
            <button className="button-primary inline-flex items-center gap-2" type="submit" disabled={submitting || validatingImage || imageFileRejected} aria-busy={submitting || validatingImage}>
              {submitting || validatingImage ? <LoaderCircle className="animate-spin motion-reduce:animate-none" size={18} aria-hidden="true" /> : null}
              {validatingImage ? "Controllo foto…" : uploadingImage ? "Caricamento foto…" : submitting ? (isEdit ? "Salvataggio…" : "Pubblicazione…") : (isEdit ? "Salva modifiche" : "Pubblica evento")}
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
