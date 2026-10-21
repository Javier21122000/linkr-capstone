import { ArrowLeft, ArrowRight, LoaderCircle, LogIn, Plus, X } from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link, useBlocker, useNavigate } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { useEventCover } from "../../hooks/useEventCover";
import { usePlaceResolution } from "../../hooks/usePlaceResolution";
import { createEvent, getCreateEventFailure } from "../../lib/eventCreation";
import { buildEventPayload, mapServerFieldErrors } from "../../lib/eventForm";
import type { EventFormValues } from "../../lib/eventForm";
import { uploadEventImage } from "../../lib/eventImages";
import type { PlaceSuggestion } from "../../lib/geocoding";
import { getMapTilerStyleUrl, isInsideMilan } from "../../lib/map";
import type { MapPickState } from "../../lib/map";
import {
  errorsForStep,
  firstErrorKey,
  hasQuickCreateProgress,
  initialQuickCreateDraft,
  quickErrorsFromServer,
  stepOfError,
  toFormValues,
  validateQuickCreate,
  withMapPoint,
  withPlaceDetail,
  withResolvedPlace,
  withSuggestion,
  withVenueName,
  draftPoint,
} from "../../lib/quickCreate";
import type { QuickCreateDraft, QuickErrorKey, QuickErrors, QuickStep } from "../../lib/quickCreate";
import type { AmbiguousChoice, QuickDateChoice } from "../../lib/quickCreateSchedule";
import type { EventCategory, EventDetail } from "../../types/event";
import { IconButton } from "../ui/IconButton";
import { MapPointBar } from "./MapPointBar";
import { MapNotice } from "./MapNotice";

// Il dialogo (menu, passaggi, ruota dell'orario, fotocamera) non serve al primo paint di Discover: si scarica al bisogno.
// Lo stato resta qui (QuickCreate non si smonta), quindi bozza e passaggio sopravvivono comunque alla chiusura del dialogo.
const loadDialog = () => import("./QuickCreateDialog");
const loadMenu = () => import("./QuickCreateMenu");
const loadSteps = () => import("./QuickCreateSteps");
const QuickCreateDialog = lazy(() => loadDialog().then((module) => ({ default: module.QuickCreateDialog })));
const QuickCreateMenu = lazy(() => loadMenu().then((module) => ({ default: module.QuickCreateMenu })));
const QuickCreateStepWhat = lazy(() => loadSteps().then((module) => ({ default: module.QuickCreateStepWhat })));
const QuickCreateStepWhere = lazy(() => loadSteps().then((module) => ({ default: module.QuickCreateStepWhere })));
const QuickCreateStepDetails = lazy(() => loadSteps().then((module) => ({ default: module.QuickCreateStepDetails })));

/** Scarica le parti del dialogo prima del tocco (passaggio sul pulsante, focus, o a Discover già pronto), così l'apertura è immediata. */
function prefetchDialog() {
  void loadDialog();
  void loadMenu();
  void loadSteps();
}

type View = "closed" | "menu" | "flow" | "discard";

interface PublishedNotice {
  readonly key: number;
  readonly eventId: string;
  readonly title: string;
  /** Il filtro categoria attivo nasconde l'uscita appena pubblicata: l'avviso lo dice, invece di lasciar credere che il marker non ci sia. */
  readonly hiddenByFilter: boolean;
}

interface QuickCreateProps {
  /** Discover resta montato anche quando si naviga altrove: `false` = nascosto, il dialogo non deve restare sopra altre pagine. */
  readonly active: boolean;
  /** Il pannello lista (mobile/tablet) copre l'angolo del pulsante. */
  readonly buttonHidden: boolean;
  readonly mapPick: MapPickState | null;
  readonly onMapPickChange: (pick: MapPickState | null) => void;
  /** Evento appena pubblicato: Discover lo aggiunge alla mappa senza ricaricare la pagina. Dice se con il filtro attuale si vede. */
  readonly onCreated: (event: EventDetail) => "shown" | "hidden-by-filter";
  /** Toglie il filtro categoria e porta l'evento in vista (vedi `DiscoverPage`). */
  readonly onShowOnMap: (eventId: string) => void;
}

const STEP_TITLES: Record<QuickStep, string> = {
  1: "Cosa organizzi?",
  2: "Dove e quando?",
  3: "Ultimi dettagli",
};

const NOTICE_DURATION_MS = 8_000;

/** Dove mettere a fuoco un campo con errore. La categoria è un gruppo di chip: ha il proprio contenitore. */
function focusField(key: QuickErrorKey) {
  window.requestAnimationFrame(() => {
    const ids: Record<QuickErrorKey, string[]> = {
      title: ["qc-title"],
      category: [],
      venueName: ["qc-venue"],
      point: ["qc-venue"],
      address: ["qc-address"],
      neighbourhood: ["qc-neighbourhood"],
      date: ["qc-date", "qc-date-readonly"],
      time: ["qc-time"],
      description: ["qc-description"],
      imageAlt: ["qc-imageAlt"],
    };
    const target = ids[key].map((id) => document.getElementById(id)).find(Boolean)
      ?? document.querySelector<HTMLElement>('.qc-dialog [data-invalid="true"]');
    target?.focus();
  });
}

/** Sessione terminata a metà: la bozza resta in memoria (Discover non viene smontato), basta riaccedere e tornare. */
function SessionLostView({ onClose, onLeave }: { readonly onClose: () => void; readonly onLeave: () => void }) {
  const loginRef = useRef<HTMLAnchorElement>(null);
  useEffect(() => loginRef.current?.focus(), []);

  return (
    <div className="qc-view">
      <div className="qc-dialog__body">
        <p className="section-kicker">Sessione</p>
        <h2 id="qc-expired-title" className="mt-1 text-2xl font-extrabold tracking-[-0.045em] text-ink">La sessione è scaduta</h2>
        <p className="mt-3 text-sm leading-6 text-muted">
          Accedi di nuovo per pubblicare. Quello che hai scritto resta qui e lo ritrovi tornando su Discover.
        </p>
      </div>
      <footer className="qc-dialog__footer">
        <button className="button-secondary inline-flex items-center" type="button" onClick={onClose}>Chiudi</button>
        <Link ref={loginRef} className="button-primary inline-flex flex-1 items-center gap-2" to="/login" state={{ from: { pathname: "/" } }} onClick={onLeave}>
          <LogIn size={17} aria-hidden="true" /> Accedi
        </Link>
      </footer>
    </div>
  );
}

/**
 * Creazione rapida di un'uscita, ospitata da Discover.
 *
 * Possiede tutto lo stato (bozza, passaggio, foto): chiudere il dialogo per scegliere un punto sulla mappa, o perché Discover
 * viene nascosto, non perde nulla. Non esiste un secondo sistema di eventi: usa `createEvent`, `uploadEventImage`,
 * `validateEventForm` e `buildEventPayload` del form completo; l'evento è sempre COMMUNITY.
 */
export function QuickCreate({ active, buttonHidden, mapPick, onMapPickChange, onCreated, onShowOnMap }: QuickCreateProps) {
  const { status, user, authorizedRequest } = useAuth();
  const navigate = useNavigate();
  const mapAvailable = getMapTilerStyleUrl() !== null;
  const authenticated = status === "authenticated";

  const [view, setView] = useState<View>("closed");
  const [step, setStep] = useState<QuickStep>(1);
  const [draft, setDraft] = useState<QuickCreateDraft>(initialQuickCreateDraft);
  const [errors, setErrors] = useState<QuickErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<PublishedNotice | null>(null);

  const buttonRef = useRef<HTMLButtonElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const submittingRef = useRef(false);
  const returnViewRef = useRef<"menu" | "flow">("menu");
  const returnStepRef = useRef<QuickStep>(1);

  const picking = mapPick !== null;

  const patchValues = useCallback((patch: Partial<EventFormValues>) => {
    setDraft((current) => ({ ...current, values: { ...current.values, ...patch } }));
  }, []);

  const clearErrors = useCallback((...keys: QuickErrorKey[]) => {
    setErrors((current) => {
      if (!keys.some((key) => current[key])) return current;
      const next = { ...current };
      for (const key of keys) delete next[key];
      return next;
    });
    setFormError(null);
  }, []);

  const cover = useEventCover({
    onUrlChange: (value) => patchValues({ imageUrl: value }),
    onFileAccepted: () => setFormError(null),
  });

  // Una scelta di luogo, un solo gesto: indirizzo e quartiere mancanti si recuperano da soli (vedi `usePlaceResolution`).
  const placeResolution = usePlaceResolution((point, completed) => {
    setDraft((current) => withResolvedPlace(current, point, completed));
    clearErrors("address", "neighbourhood", "point");
  });

  const dismissNotice = useCallback(() => setNotice(null), []);

  useEffect(() => {
    if (!active || !authenticated) return undefined;
    const idle = window.requestIdleCallback ?? ((callback: () => void) => window.setTimeout(callback, 1_500));
    const cancelIdle = window.cancelIdleCallback ?? window.clearTimeout;
    const handle = idle(prefetchDialog);
    return () => cancelIdle(handle as number);
  }, [active, authenticated]);

  const hasImage = Boolean(cover.selectedImage || draft.values.imageUrl.trim());
  const dialogOpen = view !== "closed";

  function focusButton() {
    window.requestAnimationFrame(() => buttonRef.current?.focus());
  }

  function resetAll() {
    placeResolution.cancel();
    setDraft(initialQuickCreateDraft);
    setErrors({});
    setFormError(null);
    setStep(1);
    cover.reset();
  }

  function closeAll() {
    resetAll();
    setView("closed");
    focusButton();
  }

  function requestClose() {
    if (submittingRef.current) return;
    if (view === "discard") {
      setView("flow");
      return;
    }
    if (view === "flow" && hasQuickCreateProgress(draft, hasImage)) {
      setView("discard");
      return;
    }
    closeAll();
  }

  // ——— Scelta del punto sulla mappa ———

  function startPick(from: "menu" | "flow") {
    returnViewRef.current = from;
    // Dal menu si riparte dal primo passaggio (il punto è solo l'inizio); dal passaggio "Dove" si torna lì.
    returnStepRef.current = from === "flow" ? step : 1;
    onMapPickChange({ point: draftPoint(draft) });
    setView("closed");
  }

  function confirmPick() {
    const point = mapPick?.point;
    if (!point || !isInsideMilan(point.latitude, point.longitude)) return;
    setDraft((current) => withMapPoint(current, point.latitude, point.longitude));
    clearErrors("venueName", "point");
    void placeResolution.resolve({
      latitude: point.latitude,
      longitude: point.longitude,
      place: { venueName: "", landmark: false },
      origin: "map",
    });
    setStep(returnStepRef.current);
    setView("flow");
    onMapPickChange(null);
  }

  function cancelPick() {
    onMapPickChange(null);
    setView(returnViewRef.current);
  }

  // La bozza appartiene a chi l'ha scritta. Se la sessione scade o si esce, resta per chi rientra con lo stesso account
  // (è la promessa di "Sessione scaduta"); entrando con un altro account non deve comparire né poter essere pubblicata per errore.
  const userId = user?.id ?? null;
  const draftOwnerRef = useRef<string | null>(null);
  const discardForOtherUserRef = useRef(() => {});
  discardForOtherUserRef.current = () => {
    resetAll();
    setView("closed");
    setNotice(null);
  };
  useEffect(() => {
    if (userId === null) return;
    if (draftOwnerRef.current !== null && draftOwnerRef.current !== userId) discardForOtherUserRef.current();
    draftOwnerRef.current = userId;
  }, [userId]);

  // Escape annulla la scelta del punto; il listener legge sempre l'ultima versione della funzione.
  const cancelPickRef = useRef(cancelPick);
  cancelPickRef.current = cancelPick;
  const requestCloseRef = useRef(requestClose);
  requestCloseRef.current = requestClose;
  const pickingRef = useRef(picking);
  pickingRef.current = picking;

  // Il tasto Indietro (o il gesto del telefono) con il dialogo aperto o durante la scelta del punto fa quello che farebbe Escape,
  // invece di lasciare Discover con la bozza a metà. Le uscite volute (form completo, accesso) passano da `allowNavigation`.
  const guardRef = useRef(false);
  guardRef.current = active && (dialogOpen || picking);
  const leaveRef = useRef(false);
  const blocker = useBlocker(() => guardRef.current && !leaveRef.current);
  useEffect(() => {
    if (blocker.state !== "blocked") return;
    blocker.reset();
    if (pickingRef.current) cancelPickRef.current();
    else requestCloseRef.current();
  }, [blocker, blocker.state]);

  function allowNavigation() {
    leaveRef.current = true;
    window.setTimeout(() => { leaveRef.current = false; }, 0);
  }
  useEffect(() => {
    if (!picking) return undefined;
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") cancelPickRef.current();
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [picking]);

  // Discover nascosto: né punto in attesa né avviso restano appesi a una pagina che non si vede.
  useEffect(() => {
    if (active) return;
    if (picking) {
      onMapPickChange(null);
      setView(returnViewRef.current);
    }
    setNotice(null);
  }, [active, picking, onMapPickChange]);

  // Ogni passaggio parte dall'alto: il contenuto del precedente può essere più lungo.
  useEffect(() => {
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
  }, [step, view]);

  // ——— Passaggi ———

  function goToStep(next: QuickStep) {
    setStep(next);
    setFormError(null);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submittingRef.current || cover.validating) return;

    if (step === 3) {
      void publish();
      return;
    }

    const all = validateQuickCreate(draft, new Date(), Boolean(cover.selectedImage), mapAvailable);
    const stepErrors = errorsForStep(all, step);
    setErrors(stepErrors);
    setFormError(null);
    const first = firstErrorKey(stepErrors);
    if (first) {
      focusField(first);
      return;
    }
    goToStep((step + 1) as QuickStep);
  }

  async function publish() {
    // Un file scartato dai controlli va sostituito; un upload fallito invece si può ritentare così com'è (vedi useEventCover).
    if (cover.fileRejected) {
      window.requestAnimationFrame(() => document.getElementById("event-cover-file-error")?.focus());
      return;
    }

    // Tutti i passaggi di nuovo, con l'ora attuale: nel frattempo "oggi alle 20:00" può essere diventato passato.
    const now = new Date();
    const all = validateQuickCreate(draft, now, Boolean(cover.selectedImage), mapAvailable);
    const first = firstErrorKey(all);
    if (first) {
      setErrors(all);
      setFormError(null);
      goToStep(stepOfError(first));
      focusField(first);
      return;
    }

    submittingRef.current = true;
    setSubmitting(true);
    setFormError(null);
    try {
      let imageUrl = draft.values.imageUrl.trim();
      if (cover.selectedImage) {
        const uploadedUrl = await cover.uploadSelected((file) => authorizedRequest((accessToken) => uploadEventImage(accessToken, file)));
        if (uploadedUrl === null) {
          window.requestAnimationFrame(() => document.getElementById("event-cover-file-error")?.focus());
          return;
        }
        imageUrl = uploadedUrl;
      }

      // Un testo alternativo senza foto non ha senso: non si invia (la foto può essere stata rimossa dopo averlo scritto).
      const values = { ...toFormValues(draft, now), ...(imageUrl ? {} : { imageAlt: "" }) };
      const payload = buildEventPayload(values, imageUrl, "COMMUNITY");
      const created = await authorizedRequest((accessToken) => createEvent(accessToken, payload));
      if (!created.id) throw new Error("Event response is missing the event id");

      const visibility = onCreated(created);
      setNotice({ key: Date.now(), eventId: created.id, title: created.title, hiddenByFilter: visibility === "hidden-by-filter" });
      resetAll();
      setView("closed");
      focusButton();
    } catch (error) {
      const failure = getCreateEventFailure(error);
      const serverErrors = quickErrorsFromServer(mapServerFieldErrors(failure.fieldErrors));
      setFormError(failure.message);
      const firstServerError = firstErrorKey(serverErrors);
      if (firstServerError) {
        setErrors(serverErrors);
        setStep(stepOfError(firstServerError));
        focusField(firstServerError);
      } else {
        window.requestAnimationFrame(() => formRef.current?.querySelector<HTMLElement>('[role="alert"]')?.focus());
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  function chooseDate(choice: QuickDateChoice) {
    setDraft((current) => ({ ...current, dateChoice: choice, ambiguousChoice: "first" }));
    clearErrors("date", "time");
    if (choice === "custom") window.requestAnimationFrame(() => document.getElementById("qc-date")?.focus());
  }

  function selectPlace(suggestion: PlaceSuggestion) {
    setDraft((current) => withSuggestion(current, suggestion));
    clearErrors("venueName", "point", "address", "neighbourhood");
    void placeResolution.resolve({ latitude: suggestion.latitude, longitude: suggestion.longitude, place: suggestion, origin: "search" });
  }

  // ——— Contenuti del dialogo ———

  const titleId = view === "menu"
    ? "qc-menu-title"
    : view === "discard"
      ? "qc-discard-title"
      : authenticated
        ? "qc-step-title"
        : "qc-expired-title";

  function renderFlow() {
    return (
      <form ref={formRef} className="qc-view" noValidate onSubmit={handleSubmit} aria-busy={submitting}>
        {/* Il focus passa al primo campo del passaggio: questo avviso fa sapere a chi usa uno screen reader dove si trova. */}
        <p className="sr-only" role="status" aria-live="polite">Passaggio {step} di 3: {STEP_TITLES[step]}</p>
        <header className="qc-dialog__header">
          <div className="min-w-0">
            <p className="section-kicker">Passaggio {step} di 3</p>
            <h2 id="qc-step-title" className="mt-1 text-2xl font-extrabold tracking-[-0.045em] text-ink">{STEP_TITLES[step]}</h2>
            <div className="qc-progress" aria-hidden="true">
              {([1, 2, 3] as const).map((number) => (
                <span key={number} data-state={number < step ? "done" : number === step ? "current" : "todo"} />
              ))}
            </div>
          </div>
          <IconButton className="shrink-0" label="Chiudi" onClick={requestClose} disabled={submitting}>
            <X size={19} aria-hidden="true" />
          </IconButton>
        </header>

        <div ref={bodyRef} className="qc-dialog__body">
          {formError ? <div className="form-message form-message--error mb-4" role="alert" tabIndex={-1}>{formError}</div> : null}

          {step === 1 ? (
            <QuickCreateStepWhat
              draft={draft}
              errors={errors}
              disabled={submitting}
              onTitleChange={(value) => { patchValues({ title: value }); clearErrors("title"); }}
              onCategoryChange={(category: EventCategory) => { patchValues({ category }); clearErrors("category"); }}
            />
          ) : null}

          {step === 2 ? (
            <QuickCreateStepWhere
              draft={draft}
              errors={errors}
              disabled={submitting}
              mapAvailable={mapAvailable}
              placeStatus={placeResolution.status}
              placeError={placeResolution.error}
              onRetryPlace={placeResolution.retry}
              onVenueNameChange={(value) => {
                // Cambiare il nome di un luogo scelto dai suggerimenti lo rende un altro luogo: la ricerca dei suoi dettagli non serve più.
                if (draft.placeSource === "search") placeResolution.cancel();
                setDraft((current) => withVenueName(current, value));
                clearErrors("venueName", "point", "address", "neighbourhood");
              }}
              onSelectPlace={selectPlace}
              onPlaceDetailChange={(field, value) => { setDraft((current) => withPlaceDetail(current, field, value)); clearErrors(field); }}
              onPickOnMap={() => startPick("flow")}
              onDateChoice={chooseDate}
              onCustomDate={(value) => { setDraft((current) => ({ ...current, customDate: value, ambiguousChoice: "first" })); clearErrors("date", "time"); }}
              onTimeChange={(value) => { setDraft((current) => ({ ...current, time: value, ambiguousChoice: "first" })); clearErrors("time"); }}
              onAmbiguousChoice={(choice: AmbiguousChoice) => setDraft((current) => ({ ...current, ambiguousChoice: choice }))}
            />
          ) : null}

          {step === 3 ? (
            <QuickCreateStepDetails
              draft={draft}
              errors={errors}
              disabled={submitting}
              cover={{
                selectedImage: cover.selectedImage,
                fileError: cover.fileError,
                statusMessage: cover.statusMessage,
                busy: cover.validating,
                onFileSelect: cover.selectFile,
                onRemove: cover.removeImage,
                onUrlChange: cover.changeUrl,
              }}
              onDescriptionChange={(value) => { patchValues({ description: value }); clearErrors("description"); }}
              onAltChange={(value) => { patchValues({ imageAlt: value }); clearErrors("imageAlt"); }}
              onEditStep={goToStep}
            />
          ) : null}
        </div>

        <footer className="qc-dialog__footer">
          {step > 1 ? (
            <button className="button-secondary inline-flex items-center gap-2" type="button" onClick={() => goToStep((step - 1) as QuickStep)} disabled={submitting}>
              <ArrowLeft size={17} aria-hidden="true" /> Indietro
            </button>
          ) : null}
          <button
            className="button-primary inline-flex flex-1 items-center gap-2"
            type="submit"
            disabled={submitting || cover.validating || (step === 3 && cover.fileRejected)}
            aria-busy={submitting || cover.validating}
          >
            {submitting || cover.validating ? <LoaderCircle className="animate-spin motion-reduce:animate-none" size={18} aria-hidden="true" /> : null}
            {step < 3
              ? <>Continua <ArrowRight size={17} aria-hidden="true" /></>
              : cover.validating
                ? "Controllo foto…"
                : cover.uploading
                  ? "Caricamento foto…"
                  : submitting
                    ? "Pubblicazione…"
                    : "Pubblica uscita"}
          </button>
        </footer>
      </form>
    );
  }

  function renderDiscard() {
    return (
      <div className="qc-view">
        <div className="qc-dialog__body">
          <h2 id="qc-discard-title" className="text-xl font-extrabold tracking-[-0.035em] text-ink">Scartare questa uscita?</h2>
          <p className="mt-3 text-sm leading-6 text-muted">Quello che hai scritto andrà perso.</p>
        </div>
        <footer className="qc-dialog__footer">
          <button className="button-secondary inline-flex items-center" type="button" onClick={closeAll}>Scarta</button>
          <button className="button-primary inline-flex flex-1 items-center" type="button" onClick={() => setView("flow")} autoFocus>
            Continua a creare
          </button>
        </footer>
      </div>
    );
  }

  const pickPoint = mapPick?.point ?? null;

  return (
    <>
      {!buttonHidden && !picking ? (
        <IconButton
          ref={buttonRef}
          variant="accent"
          className="quick-create-fab"
          label="Crea un’uscita o un evento"
          aria-haspopup="dialog"
          aria-expanded={dialogOpen}
          data-map-inset="right"
          onPointerEnter={prefetchDialog}
          onFocus={prefetchDialog}
          onClick={() => setView("menu")}
        >
          <Plus size={26} strokeWidth={2.6} aria-hidden="true" />
        </IconButton>
      ) : null}

      {picking ? (
        <MapPointBar
          hasPoint={pickPoint !== null}
          outsideMilan={pickPoint !== null && !isInsideMilan(pickPoint.latitude, pickPoint.longitude)}
          onConfirm={confirmPick}
          onCancel={cancelPick}
        />
      ) : null}

      <div className="qc-notice-region" role="status" aria-live="polite">
        {notice ? (
          <MapNotice
            key={notice.key}
            tone="success"
            title="Uscita pubblicata"
            detail={notice.hiddenByFilter ? `${notice.title} · Il filtro attivo la nasconde dalla mappa.` : notice.title}
            durationMs={notice.hiddenByFilter ? NOTICE_DURATION_MS * 2 : NOTICE_DURATION_MS}
            onDismiss={dismissNotice}
          >
            {notice.hiddenByFilter ? (
              <button className="qc-link-button" type="button" onClick={() => { onShowOnMap(notice.eventId); setNotice(null); }}>Mostra tutte</button>
            ) : null}
            <Link className="qc-link-button" to={`/events/${encodeURIComponent(notice.eventId)}`}>Apri</Link>
          </MapNotice>
        ) : null}
      </div>

      {dialogOpen && active ? (
        <Suspense fallback={null}>
          <QuickCreateDialog labelledBy={titleId} onRequestClose={requestClose}>
            {view === "menu" ? (
              <QuickCreateMenu
                authenticated={authenticated}
                mapAvailable={mapAvailable}
                onClose={requestClose}
                onStartQuick={() => { goToStep(1); setView("flow"); }}
                onStartPick={() => startPick("menu")}
                onStartFull={() => { allowNavigation(); closeAll(); navigate("/events/new"); }}
                onLeave={() => { allowNavigation(); setView("closed"); }}
              />
            ) : null}
            {view === "flow" ? (authenticated ? renderFlow() : <SessionLostView onClose={requestClose} onLeave={allowNavigation} />) : null}
            {view === "discard" ? renderDiscard() : null}
          </QuickCreateDialog>
        </Suspense>
      ) : null}
    </>
  );
}
