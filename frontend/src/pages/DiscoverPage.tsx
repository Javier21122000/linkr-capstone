import { List, RotateCcw } from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router";

import { CategoryFilterBar } from "../components/events/CategoryFilterBar";
import { EventList } from "../components/events/EventList";
import type { MapSelection, MapStatus } from "../components/events/EventMap";
import { EventPreview } from "../components/events/EventPreview";
import { MapNotice } from "../components/events/MapNotice";
import { QuickCreate } from "../components/events/QuickCreate";
import { useLeaving } from "../hooks/useLeaving";
import { EmptyEventsIllustration } from "../components/ui/EmptyEventsIllustration";
import { EmptyState } from "../components/ui/EmptyState";
import { GlassSurface } from "../components/ui/GlassSurface";
import { useMyParticipations } from "../context/MyParticipationsContext";
import { HelpButton, useProductGuidance } from "../context/ProductGuidanceContext";
import { categoryOptions } from "../lib/eventPresentation";
import { getEvents, getEventsErrorMessage, isRequestCancelled, toEventSummary } from "../lib/events";
import { isDiscoverReady } from "../lib/guidance";
import { hasValidCoordinates } from "../lib/map";
import type { MapPickState } from "../lib/map";
import { readMapFocusRequest } from "../lib/navigation";
import type { EventCategory, EventDetail, EventSummary } from "../types/event";

// MapLibre pesa più di tutto il resto dell'app: si scarica solo quando Discover viene davvero mostrato (non su login, dettaglio evento...).
// Discover resta montato dopo la prima visita, quindi la mappa si carica una volta sola e il keep-alive non cambia.
const EventMap = lazy(() => import("../components/events/EventMap").then((module) => ({ default: module.EventMap })));

/** Stesso contenitore e stesso stato "loading" della mappa vera, così layout e indicatore non saltano quando il codice arriva. */
function MapPlaceholder() {
  return (
    <div className="event-map" data-status="loading">
      <div className="event-map__canvas" aria-hidden="true" />
      <div className="event-map__loading" role="status">
        <span className="event-map__loading-dot" aria-hidden="true" />
        Caricamento mappa
      </div>
    </div>
  );
}

type EventsState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly events: readonly EventSummary[] }
  | { readonly status: "error"; readonly message: string };

const NO_EVENTS: readonly EventSummary[] = [];

function areSameEvents(a: readonly EventSummary[], b: readonly EventSummary[]): boolean {
  return a.length === b.length && a.every((event, index) => JSON.stringify(event) === JSON.stringify(b[index]));
}

/** Avviso sopra la mappa generato da Discover (es. filtro tolto per mostrare un evento). */
interface DiscoverNotice {
  readonly key: number;
  readonly title: string;
  readonly detail?: string;
  readonly actionLabel?: string;
  readonly onAction?: () => void;
}

interface DiscoverPageProps {
  /** Discover resta montato anche quando si naviga altrove: `false` = nascosto, stato e mappa conservati. */
  readonly active: boolean;
}
const DESKTOP_DISCOVERY_QUERY = "(min-width: 64rem)";

function useDesktopDiscoveryLayout(): boolean {
  const [isDesktop, setIsDesktop] = useState(() => window.matchMedia(DESKTOP_DISCOVERY_QUERY).matches);

  useEffect(() => {
    const mediaQuery = window.matchMedia(DESKTOP_DISCOVERY_QUERY);
    const handleChange = (event: MediaQueryListEvent) => setIsDesktop(event.matches);
    setIsDesktop(mediaQuery.matches);
    mediaQuery.addEventListener("change", handleChange);
    return () => mediaQuery.removeEventListener("change", handleChange);
  }, []);

  return isDesktop;
}

export function DiscoverPage({ active }: DiscoverPageProps) {
  const [activeCategory, setActiveCategory] = useState<EventCategory | null>(null);
  const [requestKey, setRequestKey] = useState(0);
  const [eventsState, setEventsState] = useState<EventsState>({ status: "loading" });
  const [selection, setSelection] = useState<MapSelection | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [mapPick, setMapPick] = useState<MapPickState | null>(null);
  const [notice, setNotice] = useState<DiscoverNotice | null>(null);
  const [mapStatus, setMapStatus] = useState<MapStatus>("loading");
  const listTriggerRef = useRef<HTMLButtonElement>(null);
  const isDesktop = useDesktopDiscoveryLayout();
  const eventsStatusRef = useRef(eventsState.status);
  const wasActiveRef = useRef(active);
  const refreshControllerRef = useRef<AbortController | null>(null);
  const focusControllerRef = useRef<AbortController | null>(null);
  const handledFocusRef = useRef<number | null>(null);
  const location = useLocation();
  const navigate = useNavigate();
  const focusRequest = readMapFocusRequest(location.state);

  eventsStatusRef.current = eventsState.status;

  // Benvenuto e tour aspettano che Discover sia davvero pronto: visibile, eventi arrivati (o falliti) e mappa finita di caricare (o di fallire).
  // Il tuo stato (Parteciperò / Mi interessa) sui marker e nella lista: dati reali dell'API, vuoti da anonimi.
  const { byEventId: participations, refresh: refreshParticipations } = useMyParticipations();
  const refreshParticipationsRef = useRef(refreshParticipations);
  refreshParticipationsRef.current = refreshParticipations;
  const { notifyDiscoverReady } = useProductGuidance();
  const discoverReady = isDiscoverReady({ active, eventsLoading: eventsState.status === "loading", mapStatus });
  useEffect(() => {
    notifyDiscoverReady(discoverReady);
    return () => notifyDiscoverReady(false);
  }, [discoverReady, notifyDiscoverReady]);

  // Si carica l'elenco completo una sola volta: la categoria è un filtro locale. Cambiare filtro è quindi istantaneo e
  // sincrono, senza richieste in volo che possano arrivare fuori ordine e senza dati di una categoria sotto la chiave di un'altra.
  useEffect(() => {
    const controller = new AbortController();
    refreshControllerRef.current?.abort();
    setEventsState({ status: "loading" });

    void getEvents(undefined, controller.signal)
      .then((events) => setEventsState({ status: "success", events }))
      .catch((error: unknown) => {
        if (!isRequestCancelled(error)) {
          setEventsState({ status: "error", message: getEventsErrorMessage(error) });
        }
      });

    return () => controller.abort();
  }, [requestKey]);

  // Al ritorno su Discover i dati si aggiornano in silenzio: la mappa non si svuota e la vista resta dov'era.
  useEffect(() => {
    const becameActive = active && !wasActiveRef.current;
    wasActiveRef.current = active;
    if (!becameActive) return;

    // Tornando su Discover si rilegge lo stato personale: potrebbe essere cambiato altrove (altra scheda, altro dispositivo).
    void refreshParticipationsRef.current();

    if (eventsStatusRef.current === "error") {
      setRequestKey((current) => current + 1);
      return;
    }
    if (eventsStatusRef.current !== "success") return;

    const controller = new AbortController();
    refreshControllerRef.current?.abort();
    refreshControllerRef.current = controller;

    void getEvents(undefined, controller.signal)
      .then((fresh) => {
        setEventsState((current) => (
          current.status === "success" && areSameEvents(current.events, fresh)
            ? current
            : { status: "success", events: fresh }
        ));
      })
      .catch(() => {
        // Il refresh è opportunistico: in caso di errore restano visibili i dati già caricati.
      });
  }, [active]);

  useEffect(() => () => {
    refreshControllerRef.current?.abort();
    focusControllerRef.current?.abort();
  }, []);

  const allEvents = eventsState.status === "success" ? eventsState.events : NO_EVENTS;
  const events = useMemo(
    () => (activeCategory ? allEvents.filter((event) => event.category === activeCategory) : allEvents),
    [allEvents, activeCategory],
  );
  const selectedEventId = selection?.id ?? null;
  const selectedEvent = events.find((event) => event.id === selectedEventId) ?? null;
  // L'anteprima esce con un breve movimento quando si chiude o si cambia filtro, invece di sparire di colpo.
  const preview = useLeaving(selectedEvent && !mapPick ? selectedEvent : null);
  const selectedLabel = categoryOptions.find((option) => option.value === activeCategory)?.label ?? "Tutte";

  // Un refresh può togliere l'evento selezionato (annullato, terminato): la selezione non deve restare appesa.
  useEffect(() => {
    if (eventsState.status === "success" && selectedEventId && !events.some((event) => event.id === selectedEventId)) {
      setSelection(null);
    }
  }, [eventsState.status, events, selectedEventId]);

  const closeList = useCallback(() => {
    setListOpen(false);
    window.requestAnimationFrame(() => listTriggerRef.current?.focus());
  }, []);
  const selectFromMap = useCallback((eventId: string) => setSelection({ id: eventId, source: "map" }), []);
  const selectFromList = useCallback((eventId: string) => {
    setSelection({ id: eventId, source: "list" });
    if (!isDesktop) closeList();
  }, [closeList, isDesktop]);

  // Evento appena pubblicato con Quick Create: entra nell'elenco in ordine di inizio, senza ricaricare né azzerare la vista.
  // Se il filtro attivo lo nasconde, il filtro resta com'è e l'avviso di Quick Create offre il link al dettaglio.
  const addCreatedEvent = useCallback((created: EventDetail): "shown" | "hidden-by-filter" => {
    const summary = toEventSummary(created);
    setEventsState((current) => {
      if (current.status !== "success") return current;
      const others = current.events.filter((event) => event.id !== summary.id);
      const events = [...others, summary].sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt) || a.title.localeCompare(b.title, "it"));
      return { status: "success", events };
    });
    if (activeCategory === null || activeCategory === summary.category) {
      setSelection({ id: summary.id, source: "list" });
      return "shown";
    }
    return "hidden-by-filter";
  }, [activeCategory]);

  function selectCategory(category: EventCategory | null) {
    setSelection(null);
    setActiveCategory(category);
  }

  /**
   * Porta un evento in vista sulla mappa già aperta: seleziona, inquadra e mostra l'anteprima. Non crea mappe né ricarica la pagina.
   * Se il filtro categoria lo nasconde, il filtro viene tolto e un avviso lo dice (con "Ripristina"): il marker non "sparisce" in silenzio.
   * Un evento non ancora nell'elenco (appena creato altrove) si cerca con un aggiornamento prima di dire che non c'è.
   */
  const focusEventOnMap = useCallback(async (eventId: string) => {
    focusControllerRef.current?.abort();
    const controller = new AbortController();
    focusControllerRef.current = controller;

    let known = allEvents;
    let event = known.find((candidate) => candidate.id === eventId);
    if (!event) {
      try {
        known = await getEvents(undefined, controller.signal);
        if (controller.signal.aborted) return;
        setEventsState({ status: "success", events: known });
        event = known.find((candidate) => candidate.id === eventId);
      } catch (error) {
        if (isRequestCancelled(error)) return;
      }
    }

    if (!event || !hasValidCoordinates(event)) {
      setNotice({
        key: Date.now(),
        title: "Questo evento non è sulla mappa",
        detail: "È terminato, è stato annullato o non ha una posizione.",
      });
      return;
    }

    setListOpen(false);
    const previousCategory = activeCategory;
    if (previousCategory !== null && event.category !== previousCategory) {
      setActiveCategory(null);
      setNotice({
        key: Date.now(),
        title: "Filtro tolto per mostrarti l’evento",
        detail: `Era attivo «${categoryOptions.find((option) => option.value === previousCategory)?.label ?? "categoria"}».`,
        actionLabel: "Ripristina",
        onAction: () => selectCategory(previousCategory),
      });
    }
    setSelection({ id: eventId, source: "list" });
  // `selectCategory` usa solo setter: non serve tra le dipendenze.
  }, [allEvents, activeCategory]);

  // "Visualizza sulla mappa" da un'altra pagina: arriva con lo stato di navigazione. Si gestisce una volta e lo stato si toglie
  // dalla voce di cronologia, così tornare indietro o ricaricare non rimette la camera dove l'utente non la vuole più.
  const focusEventId = focusRequest?.eventId;
  const focusRequestedAt = focusRequest?.requestedAt;
  useEffect(() => {
    if (!active || focusEventId === undefined || focusRequestedAt === undefined) return;
    if (handledFocusRef.current === focusRequestedAt || eventsState.status === "loading") return;

    handledFocusRef.current = focusRequestedAt;
    navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: null });
    if (eventsState.status === "error") {
      setNotice({ key: Date.now(), title: "Non riesco a mostrarti l’evento", detail: "L’elenco degli eventi non è disponibile adesso." });
      return;
    }
    void focusEventOnMap(focusEventId);
  }, [active, eventsState.status, focusEventId, focusRequestedAt, focusEventOnMap, location.pathname, location.search, navigate]);

  // Un avviso non resta appeso a una pagina nascosta.
  useEffect(() => {
    if (!active) setNotice(null);
  }, [active]);
  const dismissNotice = useCallback(() => setNotice(null), []);

  return (
    <div className="discover" data-layout={isDesktop ? "split" : "overlay"}>
      <h1 id="discover-title" className="sr-only">Scopri eventi a Milano</h1>

      {/* Ordine di lettura e di tabulazione: filtri → lista → mappa (i filtri comandano la lista). Il posizionamento è tutto CSS. */}
      <CategoryFilterBar activeCategory={activeCategory} onSelect={selectCategory} />

      {isDesktop && (
        <EventList
          variant="panel"
          events={events}
          selectedEventId={selectedEventId}
          participations={participations}
          autoScrollToSelection
          loading={eventsState.status === "loading"}
          errorMessage={eventsState.status === "error" ? eventsState.message : undefined}
          activeCategoryLabel={activeCategory ? selectedLabel : undefined}
          onSelectEvent={selectFromList}
          onRetry={() => setRequestKey((current) => current + 1)}
          onShowAll={activeCategory ? () => selectCategory(null) : undefined}
        />
      )}

      <section className="discover-map-stage" aria-labelledby="discover-title" data-tour="map">
        <Suspense fallback={<MapPlaceholder />}>
          <EventMap
            events={events}
            eventsReady={eventsState.status === "success"}
            viewKey={activeCategory ?? "all"}
            selection={selection}
            active={active}
            onSelectEvent={selectFromMap}
            pick={mapPick}
            onPickPoint={(point) => setMapPick({ point })}
            onStatusChange={setMapStatus}
            participations={participations}
          />
        </Suspense>

        <p className="sr-only" aria-live="polite">
          {eventsState.status === "loading" && "Caricamento eventi in corso"}
          {eventsState.status === "error" && "Impossibile caricare gli eventi"}
          {eventsState.status === "success" && `${events.length} eventi mostrati per la categoria ${selectedLabel}`}
        </p>

        {eventsState.status === "loading" && (
          <GlassSurface tone="strong" className="discover-map-message discover-map-message--compact" role="status">
            Cerco eventi a Milano…
          </GlassSurface>
        )}

        {!isDesktop && eventsState.status === "error" && !listOpen && (
          <GlassSurface tone="strong" className="discover-map-message" role="alert">
            <EmptyState
              title="Eventi non disponibili"
              description={eventsState.message}
              action={(
                <button className="button-primary inline-flex items-center gap-2" type="button" onClick={() => setRequestKey((current) => current + 1)}>
                  <RotateCcw size={16} aria-hidden="true" /> Riprova
                </button>
              )}
            />
          </GlassSurface>
        )}

        {!isDesktop && eventsState.status === "success" && events.length === 0 && !listOpen && (
          <GlassSurface tone="strong" className="discover-map-message" role="status">
            <EmptyState
              illustration={<EmptyEventsIllustration className="w-44" />}
              title="Nessun evento vicino"
              description={activeCategory ? "Non ci sono eventi in questa categoria." : "Non ci sono eventi da mostrare adesso."}
              action={activeCategory && (
                <button className="button-secondary inline-flex items-center" type="button" onClick={() => selectCategory(null)}>
                  Mostra tutti gli eventi
                </button>
              )}
            />
          </GlassSurface>
        )}

        {!isDesktop && !listOpen && !mapPick && eventsState.status === "success" && events.length > 0 && (
          <button
            ref={listTriggerRef}
            className="map-list-trigger glass glass--strong"
            data-map-inset="bottom"
            data-tour="list"
            type="button"
            onClick={() => setListOpen(true)}
            aria-haspopup="dialog"
          >
            <List size={19} aria-hidden="true" /> Mostra lista <span>{events.length}</span>
          </button>
        )}

        {preview.shown && (!listOpen || isDesktop) && (
          <EventPreview event={preview.shown} participation={participations.get(preview.shown.id)} leaving={preview.leaving} onClose={() => setSelection(null)} />
        )}

        <QuickCreate
          active={active}
          buttonHidden={listOpen && !isDesktop}
          mapPick={mapPick}
          onMapPickChange={setMapPick}
          onCreated={addCreatedEvent}
          onShowOnMap={(eventId) => void focusEventOnMap(eventId)}
        />
        <HelpButton hidden={listOpen && !isDesktop} />

        <div className="qc-notice-region" role="status" aria-live="polite">
          {notice ? (
            <MapNotice key={notice.key} title={notice.title} detail={notice.detail} durationMs={notice.onAction ? 12_000 : 8_000} onDismiss={dismissNotice}>
              {notice.onAction ? (
                <button className="qc-link-button" type="button" onClick={() => { notice.onAction?.(); dismissNotice(); }}>{notice.actionLabel}</button>
              ) : null}
            </MapNotice>
          ) : null}
        </div>

        {!isDesktop && listOpen && active && (
          <EventList
            variant="sheet"
            events={events}
            selectedEventId={selectedEventId}
            participations={participations}
            autoScrollToSelection={false}
            loading={eventsState.status === "loading"}
            errorMessage={eventsState.status === "error" ? eventsState.message : undefined}
            activeCategoryLabel={activeCategory ? selectedLabel : undefined}
            onSelectEvent={selectFromList}
            onClose={closeList}
            onRetry={() => setRequestKey((current) => current + 1)}
            onShowAll={activeCategory ? () => selectCategory(null) : undefined}
          />
        )}
      </section>
    </div>
  );
}
