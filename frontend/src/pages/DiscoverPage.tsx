import { List, RotateCcw } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { CategoryFilterBar } from "../components/events/CategoryFilterBar";
import { EventList } from "../components/events/EventList";
import { EventMap } from "../components/events/EventMap";
import { EventPreview } from "../components/events/EventPreview";
import { EmptyEventsIllustration } from "../components/ui/EmptyEventsIllustration";
import { EmptyState } from "../components/ui/EmptyState";
import { GlassSurface } from "../components/ui/GlassSurface";
import { categoryOptions } from "../lib/eventPresentation";
import { getEvents, getEventsErrorMessage, isRequestCancelled } from "../lib/events";
import type { EventCategory, EventSummary } from "../types/event";

type EventsState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly events: readonly EventSummary[] }
  | { readonly status: "error"; readonly message: string };

const NO_EVENTS: readonly EventSummary[] = [];

function areSameEvents(a: readonly EventSummary[], b: readonly EventSummary[]): boolean {
  return a.length === b.length && a.every((event, index) => JSON.stringify(event) === JSON.stringify(b[index]));
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
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const listTriggerRef = useRef<HTMLButtonElement>(null);
  const isDesktop = useDesktopDiscoveryLayout();
  const categoryRef = useRef(activeCategory);
  const eventsStatusRef = useRef(eventsState.status);
  const wasActiveRef = useRef(active);
  const refreshControllerRef = useRef<AbortController | null>(null);

  categoryRef.current = activeCategory;
  eventsStatusRef.current = eventsState.status;

  useEffect(() => {
    const controller = new AbortController();
    refreshControllerRef.current?.abort();
    setEventsState({ status: "loading" });

    void getEvents(activeCategory ?? undefined, controller.signal)
      .then((events) => setEventsState({ status: "success", events }))
      .catch((error: unknown) => {
        if (!isRequestCancelled(error)) {
          setEventsState({ status: "error", message: getEventsErrorMessage(error) });
        }
      });

    return () => controller.abort();
  }, [activeCategory, requestKey]);

  // Al ritorno su Discover i dati si aggiornano in silenzio: la mappa non si svuota e la vista resta dov'era.
  useEffect(() => {
    const becameActive = active && !wasActiveRef.current;
    wasActiveRef.current = active;
    if (!becameActive) return;

    if (eventsStatusRef.current === "error") {
      setRequestKey((current) => current + 1);
      return;
    }
    if (eventsStatusRef.current !== "success") return;

    const category = categoryRef.current;
    const controller = new AbortController();
    refreshControllerRef.current?.abort();
    refreshControllerRef.current = controller;

    void getEvents(category ?? undefined, controller.signal)
      .then((fresh) => {
        if (categoryRef.current !== category) return;
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

  useEffect(() => () => refreshControllerRef.current?.abort(), []);

  const events = eventsState.status === "success" ? eventsState.events : NO_EVENTS;
  const selectedEvent = events.find((event) => event.id === selectedEventId) ?? null;
  const selectedLabel = categoryOptions.find((option) => option.value === activeCategory)?.label ?? "Tutte";

  useEffect(() => {
    if (eventsState.status === "success" && selectedEventId && !eventsState.events.some((event) => event.id === selectedEventId)) {
      setSelectedEventId(null);
    }
  }, [eventsState, selectedEventId]);

  const closeList = useCallback(() => {
    setListOpen(false);
    window.requestAnimationFrame(() => listTriggerRef.current?.focus());
  }, []);
  const selectFromMap = useCallback((eventId: string) => setSelectedEventId(eventId), []);
  const selectFromList = useCallback((eventId: string) => {
    setSelectedEventId(eventId);
    if (!isDesktop) closeList();
  }, [closeList, isDesktop]);

  function selectCategory(category: EventCategory | null) {
    setSelectedEventId(null);
    setActiveCategory(category);
  }

  return (
    <div className="discover" data-layout={isDesktop ? "split" : "overlay"}>
      {isDesktop && (
        <EventList
          variant="panel"
          events={events}
          selectedEventId={selectedEventId}
          autoScrollToSelection
          loading={eventsState.status === "loading"}
          errorMessage={eventsState.status === "error" ? eventsState.message : undefined}
          activeCategoryLabel={activeCategory ? selectedLabel : undefined}
          onSelectEvent={selectFromList}
          onRetry={() => setRequestKey((current) => current + 1)}
          onShowAll={activeCategory ? () => selectCategory(null) : undefined}
        />
      )}

      <section className="discover-map-stage" aria-labelledby="discover-title">
        <h1 id="discover-title" className="sr-only">Scopri eventi a Milano</h1>

        {/* I filtri precedono la mappa nel DOM: la tastiera li raggiunge prima dei marker (posizione: CSS assoluto). */}
        <CategoryFilterBar activeCategory={activeCategory} onSelect={selectCategory} />

        <EventMap
          events={events}
          selectedEventId={selectedEventId}
          active={active}
          fitKey={activeCategory ?? "all"}
          onSelectEvent={selectFromMap}
        />

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

        {!isDesktop && !listOpen && eventsState.status === "success" && events.length > 0 && (
          <button
            ref={listTriggerRef}
            className="map-list-trigger glass glass--strong"
            type="button"
            onClick={() => setListOpen(true)}
            aria-haspopup="dialog"
          >
            <List size={19} aria-hidden="true" /> Mostra lista <span>{events.length}</span>
          </button>
        )}

        {selectedEvent && (!listOpen || isDesktop) && (
          <EventPreview event={selectedEvent} onClose={() => setSelectedEventId(null)} />
        )}

        {!isDesktop && listOpen && active && (
          <EventList
            variant="sheet"
            events={events}
            selectedEventId={selectedEventId}
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
