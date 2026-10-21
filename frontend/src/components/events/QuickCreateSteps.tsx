import { CalendarDays, LoaderCircle, MapPin, MapPinned, Pencil, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import type { ChangeEvent } from "react";

import type { PlaceResolutionStatus } from "../../hooks/usePlaceResolution";
import { categoryLabels, categoryOptions } from "../../lib/eventPresentation";
import type { PlaceSuggestion } from "../../lib/geocoding";
import { hasDraftPoint, scheduleError } from "../../lib/quickCreate";
import type { QuickCreateDraft, QuickErrors, QuickStep } from "../../lib/quickCreate";
import { checkSchedule, formatQuickDate, formatQuickStart, resolveQuickDate, suggestTimes, todayInRome } from "../../lib/quickCreateSchedule";
import type { AmbiguousChoice, QuickDateChoice } from "../../lib/quickCreateSchedule";
import type { EventCategory } from "../../types/event";
import { FormField } from "../auth/FormField";
import { FilterChip } from "../ui/FilterChip";
import { CategoryDisc } from "./CategoryIcon";
import { EventCoverField } from "./EventCoverField";
import { PlaceAutocomplete } from "./PlaceAutocomplete";
import { TimeWheelField } from "./TimeWheelField";

/** Mette a fuoco il primo campo del passaggio quando compare: l'utente può scrivere subito, senza cercare dove. */
function useFocusOnMount(id: string) {
  useEffect(() => {
    document.getElementById(id)?.focus({ preventScroll: true });
  }, [id]);
}

const categoryChoices = categoryOptions.filter(
  (option): option is { readonly value: EventCategory; readonly label: string } => option.value !== null,
);

// ——— Passaggio 1 ———

interface StepWhatProps {
  readonly draft: QuickCreateDraft;
  readonly errors: QuickErrors;
  readonly disabled: boolean;
  readonly onTitleChange: (value: string) => void;
  readonly onCategoryChange: (category: EventCategory) => void;
}

export function QuickCreateStepWhat({ draft, errors, disabled, onTitleChange, onCategoryChange }: StepWhatProps) {
  useFocusOnMount("qc-title");

  return (
    <div className="space-y-5">
      <FormField
        id="qc-title"
        label="Titolo"
        value={draft.values.title}
        onChange={(event) => onTitleChange(event.target.value)}
        error={errors.title}
        maxLength={120}
        autoComplete="off"
        placeholder="Es. Aperitivo ai Navigli"
        disabled={disabled}
      />

      <div
        role="group"
        aria-labelledby="qc-category-label"
        aria-describedby={errors.category ? "qc-category-error" : undefined}
        tabIndex={-1}
        data-invalid={errors.category ? "true" : undefined}
        className="qc-chip-group"
      >
        <span id="qc-category-label" className="form-label">Categoria</span>
        <div className="mt-2 flex flex-wrap gap-2">
          {categoryChoices.map((option) => (
            <FilterChip
              key={option.value}
              label={option.label}
              category={option.value}
              selected={draft.values.category === option.value}
              onSelect={() => !disabled && onCategoryChange(option.value)}
            />
          ))}
        </div>
        {errors.category ? <p id="qc-category-error" className="field-error mt-2">{errors.category}</p> : null}
      </div>
    </div>
  );
}

// ——— Passaggio 2 ———

const dateChoices: ReadonlyArray<{ readonly value: QuickDateChoice; readonly label: string }> = [
  { value: "today", label: "Oggi" },
  { value: "tomorrow", label: "Domani" },
  { value: "weekend", label: "Questo weekend" },
  { value: "custom", label: "Scegli data" },
];

interface StepWhereProps {
  readonly draft: QuickCreateDraft;
  readonly errors: QuickErrors;
  readonly disabled: boolean;
  readonly mapAvailable: boolean;
  /** Ricerca automatica di indirizzo e quartiere dopo la scelta del luogo. */
  readonly placeStatus: PlaceResolutionStatus;
  readonly placeError: string | null;
  readonly onRetryPlace: () => void;
  readonly onVenueNameChange: (value: string) => void;
  readonly onSelectPlace: (suggestion: PlaceSuggestion) => void;
  readonly onPlaceDetailChange: (field: "address" | "neighbourhood", value: string) => void;
  readonly onPickOnMap: () => void;
  readonly onDateChoice: (choice: QuickDateChoice) => void;
  readonly onCustomDate: (value: string) => void;
  readonly onTimeChange: (value: string) => void;
  readonly onAmbiguousChoice: (choice: AmbiguousChoice) => void;
}

export function QuickCreateStepWhere({
  draft,
  errors,
  disabled,
  mapAvailable,
  placeStatus,
  placeError,
  onRetryPlace,
  onVenueNameChange,
  onSelectPlace,
  onPlaceDetailChange,
  onPickOnMap,
  onDateChoice,
  onCustomDate,
  onTimeChange,
  onAmbiguousChoice,
}: StepWhereProps) {
  useFocusOnMount("qc-venue");
  const [editingDetails, setEditingDetails] = useState(false);
  const { values } = draft;
  const hasPoint = hasDraftPoint(draft);
  const now = new Date();
  const schedule = checkSchedule(draft.dateChoice, draft.customDate, draft.time, now, draft.ambiguousChoice);
  const resolvedDate = resolveQuickDate(draft.dateChoice, draft.customDate, now);
  const timeSuggestions = suggestTimes(draft.dateChoice, draft.customDate, now);

  // Un orario già trascorso o inesistente si segnala subito, non solo alla pressione di "Continua": è l'errore più facile da fare con "Oggi".
  const liveIssue = schedule.issue === "past" || schedule.issue === "nonexistent" ? schedule.issue : null;
  const timeError = errors.time ?? (liveIssue ? scheduleError(liveIssue).message : undefined);
  const resolving = placeStatus === "resolving";
  const detailsMissing = !values.address.trim() || !values.neighbourhood.trim();
  // I campi a mano compaiono solo quando servono: mentre il servizio li sta cercando no (altrimenti lampeggiano e poi spariscono).
  const showDetailFields = hasPoint && !resolving && (editingDetails || detailsMissing || Boolean(errors.address || errors.neighbourhood));
  const summaryLine = [values.address.trim(), values.neighbourhood.trim()].filter(Boolean).join(" · ");
  const missingWhat = !values.address.trim() && !values.neighbourhood.trim()
    ? "l’indirizzo e il quartiere"
    : !values.address.trim() ? "l’indirizzo" : "il quartiere";

  return (
    <div className="space-y-6">
      <section className="space-y-3" aria-labelledby="qc-where-heading">
        <h3 id="qc-where-heading" className="sr-only">Dove</h3>
        <PlaceAutocomplete
          id="qc-venue"
          label="Luogo"
          value={values.venueName}
          onChange={onVenueNameChange}
          onSelect={onSelectPlace}
          error={errors.venueName ?? errors.point}
          disabled={disabled}
          hideEmptyNotice={draft.placeSource === "map"}
        />

        {hasPoint ? (
          <div className="qc-place" data-source={draft.placeSource ?? undefined}>
            <div className="qc-place__summary">
              {resolving
                ? <LoaderCircle className="shrink-0 animate-spin text-linkr-strong motion-reduce:animate-none" size={18} aria-hidden="true" />
                : <MapPin className="shrink-0 text-linkr-strong" size={18} aria-hidden="true" />}
              <p className="min-w-0 flex-1 text-sm font-bold leading-5 text-ink" aria-live="polite">
                {resolving
                  ? <span className="text-muted">Cerco indirizzo e quartiere…</span>
                  : draft.placeSource === "map" && !summaryLine ? "Punto scelto sulla mappa" : summaryLine || "Luogo selezionato"}
                {!resolving && detailsMissing ? (
                  <span className="mt-0.5 block text-xs font-semibold text-muted">
                    {placeStatus === "failed"
                      ? `${placeError ?? "Non siamo riusciti a trovare i dettagli."} Scrivi tu ${missingWhat}.`
                      : `Non abbiamo trovato ${missingWhat} di questo punto: scrivili tu.`}
                  </span>
                ) : null}
              </p>
              {!resolving && placeStatus === "failed" ? (
                <button className="qc-link-button" type="button" onClick={onRetryPlace}>
                  <RotateCcw size={14} aria-hidden="true" /> Riprova
                </button>
              ) : null}
              {!resolving && !detailsMissing ? (
                <button className="qc-link-button" type="button" onClick={() => setEditingDetails((current) => !current)} aria-expanded={editingDetails}>
                  <Pencil size={14} aria-hidden="true" /> {editingDetails ? "Fatto" : "Modifica"}
                </button>
              ) : null}
            </div>
            {showDetailFields ? (
              <div className="mt-4 grid gap-4 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
                <FormField
                  id="qc-address"
                  label="Indirizzo"
                  value={values.address}
                  onChange={(event) => onPlaceDetailChange("address", event.target.value)}
                  error={errors.address}
                  maxLength={200}
                  autoComplete="off"
                  placeholder="Via e numero civico"
                  disabled={disabled}
                />
                <FormField
                  id="qc-neighbourhood"
                  label="Quartiere"
                  value={values.neighbourhood}
                  onChange={(event) => onPlaceDetailChange("neighbourhood", event.target.value)}
                  error={errors.neighbourhood}
                  maxLength={60}
                  autoComplete="off"
                  placeholder="Es. Isola"
                  disabled={disabled}
                />
              </div>
            ) : null}
          </div>
        ) : null}

        {mapAvailable ? (
          <button className="qc-link-button" type="button" onClick={onPickOnMap} disabled={disabled}>
            <MapPinned size={16} aria-hidden="true" /> {hasPoint ? "Sposta il punto sulla mappa" : "Oppure scegli il punto sulla mappa"}
          </button>
        ) : null}
      </section>

      <fieldset className="space-y-3" disabled={disabled}>
        <legend className="form-label">Quando</legend>
        <div className="flex flex-wrap gap-2" role="group" aria-label="Scorciatoie per il giorno">
          {dateChoices.map((choice) => (
            <FilterChip
              key={choice.value}
              label={choice.label}
              selected={draft.dateChoice === choice.value}
              onSelect={() => onDateChoice(choice.value)}
            />
          ))}
        </div>

        <div className="grid gap-4">
          {draft.dateChoice === "custom" ? (
            <FormField
              id="qc-date"
              label="Data"
              type="date"
              value={draft.customDate}
              min={todayInRome(now)}
              onChange={(event: ChangeEvent<HTMLInputElement>) => onCustomDate(event.target.value)}
              error={errors.date}
              disabled={disabled}
            />
          ) : (
            <div>
              <span className="form-label">Data</span>
              <p
                className="qc-readonly mt-2"
                data-invalid={errors.date ? "true" : undefined}
                tabIndex={-1}
                id="qc-date-readonly"
                aria-live="polite"
              >
                <CalendarDays size={17} className="shrink-0 text-linkr-strong" aria-hidden="true" />
                <span className="first-letter:uppercase">{resolvedDate ? formatQuickDate(resolvedDate) : "Scegli un giorno qui sopra"}</span>
              </p>
              {errors.date ? <p className="field-error mt-2">{errors.date}</p> : null}
            </div>
          )}
          <TimeWheelField
            id="qc-time"
            label="Ora"
            value={draft.time}
            onChange={onTimeChange}
            suggestions={timeSuggestions}
            defaultTime={timeSuggestions[0] ?? "19:00"}
            error={timeError}
            disabled={disabled}
            ambiguous={schedule.ambiguous}
            ambiguousChoice={draft.ambiguousChoice}
            onAmbiguousChoice={onAmbiguousChoice}
            referenceInstant={now.getTime()}
          />
        </div>

        {schedule.issue === "past" && draft.dateChoice === "today" ? (
          <button className="qc-link-button" type="button" onClick={() => onDateChoice("tomorrow")}>
            Usa domani alla stessa ora
          </button>
        ) : null}
      </fieldset>
    </div>
  );
}

// ——— Passaggio 3 ———

interface CoverControls {
  readonly selectedImage: File | null;
  readonly fileError?: string;
  readonly statusMessage?: string;
  readonly busy: boolean;
  readonly onFileSelect: (file: File) => void;
  readonly onRemove: () => void;
  readonly onUrlChange: (value: string) => void;
}

interface StepDetailsProps {
  readonly draft: QuickCreateDraft;
  readonly errors: QuickErrors;
  readonly disabled: boolean;
  readonly cover: CoverControls;
  readonly onDescriptionChange: (value: string) => void;
  readonly onAltChange: (value: string) => void;
  readonly onEditStep: (step: QuickStep) => void;
}

export function QuickCreateStepDetails({
  draft,
  errors,
  disabled,
  cover,
  onDescriptionChange,
  onAltChange,
  onEditStep,
}: StepDetailsProps) {
  useFocusOnMount("qc-description");
  const { values } = draft;
  const schedule = checkSchedule(draft.dateChoice, draft.customDate, draft.time, new Date(), draft.ambiguousChoice);
  const hasCover = Boolean(cover.selectedImage || values.imageUrl.trim());
  const place = [values.venueName.trim(), values.neighbourhood.trim()].filter(Boolean).join(" · ");

  return (
    <div className="space-y-5">
      <ul className="qc-summary" aria-label="Riepilogo dell’uscita">
        <li className="qc-summary__row">
          {values.category ? <CategoryDisc category={values.category} size={15} /> : null}
          <p className="min-w-0 flex-1 truncate text-sm font-extrabold text-ink">
            <span className="sr-only">Cosa: </span>
            {values.title.trim()}
            {values.category ? <span className="ml-2 text-xs font-bold text-muted">{categoryLabels[values.category]}</span> : null}
          </p>
          <button className="qc-link-button" type="button" onClick={() => onEditStep(1)} disabled={disabled} aria-label="Modifica titolo e categoria">Modifica</button>
        </li>
        <li className="qc-summary__row">
          <CalendarDays className="shrink-0 text-linkr-strong" size={17} aria-hidden="true" />
          <p className="min-w-0 flex-1 text-sm font-bold text-ink first-letter:uppercase">
            <span className="sr-only">Quando: </span>
            {formatQuickStart(schedule.startsAt)}
          </p>
          <button className="qc-link-button" type="button" onClick={() => onEditStep(2)} disabled={disabled} aria-label="Modifica data e ora">Modifica</button>
        </li>
        <li className="qc-summary__row">
          <MapPin className="shrink-0 text-linkr-strong" size={17} aria-hidden="true" />
          <p className="min-w-0 flex-1 truncate text-sm font-bold text-ink">
            <span className="sr-only">Dove: </span>
            {place}
          </p>
          <button className="qc-link-button" type="button" onClick={() => onEditStep(2)} disabled={disabled} aria-label="Modifica il luogo">Modifica</button>
        </li>
      </ul>

      <div>
        <label className="form-label" htmlFor="qc-description">Descrizione <span className="font-semibold text-muted">(facoltativa)</span></label>
        <textarea
          id="qc-description"
          name="qc-description"
          className="form-input form-textarea qc-textarea mt-2"
          value={values.description}
          onChange={(event) => onDescriptionChange(event.target.value)}
          aria-invalid={errors.description ? true : undefined}
          aria-describedby={errors.description ? "qc-description-error" : "qc-description-hint"}
          maxLength={5_000}
          placeholder="Cosa farete, per chi è e cosa serve sapere."
          disabled={disabled}
        />
        {errors.description
          ? <p id="qc-description-error" className="field-error mt-2">{errors.description}</p>
          : (
            <p id="qc-description-hint" className="mt-2 text-xs leading-5 text-muted">
              {values.description.trim()
                ? "Bastano due righe: aiutano le persone a decidere se venire."
                : "Puoi pubblicare anche senza: nelle informazioni dell’evento comparirà il titolo."}
            </p>
          )}
      </div>

      <EventCoverField
        compact
        imageUrl={values.imageUrl}
        imageAlt={values.imageAlt}
        selectedFile={cover.selectedImage}
        fileError={cover.fileError}
        statusMessage={cover.statusMessage}
        disabled={disabled || cover.busy}
        onFileSelect={cover.onFileSelect}
        onRemove={cover.onRemove}
        onUrlChange={cover.onUrlChange}
      />

      {hasCover ? (
        <details className="qc-advanced">
          <summary>Testo alternativo personalizzato</summary>
          <div className="mt-3">
            <FormField
              id="qc-imageAlt"
              label="Descrizione immagine (opzionale)"
              value={values.imageAlt}
              onChange={(event) => onAltChange(event.target.value)}
              error={errors.imageAlt}
              hint="Se lo lasci vuoto, lo generiamo dal titolo e dal luogo."
              maxLength={200}
              autoComplete="off"
              placeholder="Es. Tavolo all’aperto con amici"
              disabled={disabled}
            />
          </div>
        </details>
      ) : null}
    </div>
  );
}
