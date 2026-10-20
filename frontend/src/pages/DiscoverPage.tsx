import { List, RotateCcw } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { EventList } from "../components/events/EventList";
import { EventMap } from "../components/events/EventMap";
import { EventPreview } from "../components/events/EventPreview";
import { FilterChip } from "../components/ui/FilterChip";
import { categoryColors, categoryOptions } from "../lib/eventPresentation";
import { getEvents, getEventsErrorMessage, isRequestCancelled } from "../lib/events";
import type { EventCategory, EventSummary } from "../types/event";

type EventsState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly events: readonly EventSummary[] }
  | { readonly status: "error"; readonly message: string };

const NO_EVENTS: readonly EventSummary[] = [];
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

export function DiscoverPage() {
  const [activeCategory, setActiveCategory] = useState<EventCategory | null>(null);
  const [requestKey, setRequestKey] = useState(0);
  const [eventsState, setEventsState] = useState<EventsState>({ status: "loading" });
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const listTriggerRef = useRef<HTMLButtonElement>(null);
  const isDesktop = useDesktopDiscoveryLayout();

  useEffect(() => {
    const controller = new AbortController();
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
    <div className="discover-map-page">
      <section className="discover-map-stage" aria-labelledby="discover-title">
        <h1 id="discover-title" className="sr-only">Scopri eventi a Milano</h1>

        <EventMap
          events={events}
          selectedEventId={selectedEventId}
          desktopListOpen={isDesktop && listOpen}
          onSelectEvent={selectFromMap}
        />

        <div className="discover-filter-bar scrollbar-none" aria-label="Filtra gli eventi per categoria">
          {categoryOptions.map((option) => (
            <FilterChip
              key={option.value ?? "all"}
              label={option.label}
              indicatorColor={option.value ? categoryColors[option.value] : undefined}
              selected={activeCategory === option.value}
              onSelect={() => selectCategory(option.value)}
            />
          ))}
        </div>

        <p className="sr-only" aria-live="polite">
          {eventsState.status === "loading" && "Caricamento eventi in corso"}
          {eventsState.status === "error" && "Impossibile caricare gli eventi"}
          {eventsState.status === "success" && `${events.length} eventi mostrati per la categoria ${selectedLabel}`}
        </p>

        {eventsState.status === "loading" && (
          <div className="discover-map-message discover-map-message--compact" role="status">
            Cerco eventi a Milano…
          </div>
        )}

        {eventsState.status === "error" && !listOpen && (
          <div className="discover-map-message" role="alert">
            <p className="font-extrabold text-ink">Eventi non disponibili</p>
            <p className="mt-1 text-xs leading-5 text-muted">{eventsState.message}</p>
            <button className="button-primary mt-3 inline-flex items-center gap-2" type="button" onClick={() => setRequestKey((current) => current + 1)}>
              <RotateCcw size={16} aria-hidden="true" /> Riprova
            </button>
          </div>
        )}

        {eventsState.status === "success" && events.length === 0 && !listOpen && (
          <div className="discover-map-message" role="status">
            <p className="font-extrabold text-ink">Nessun evento in questa categoria</p>
            {activeCategory && (
              <button className="mt-2 min-h-11 rounded-full px-3 text-sm font-extrabold text-linkr-strong" type="button" onClick={() => selectCategory(null)}>
                Mostra tutti gli eventi
              </button>
            )}
          </div>
        )}

        {!listOpen && eventsState.status === "success" && events.length > 0 && (
          <button
            ref={listTriggerRef}
            className="map-list-trigger"
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

        {listOpen && (
          <EventList
            events={events}
            selectedEventId={selectedEventId}
            autoScrollToSelection={isDesktop}
            loading={eventsState.status === "loading"}
            errorMessage={eventsState.status === "error" ? eventsState.message : undefined}
            onSelectEvent={selectFromList}
            onClose={closeList}
            onRetry={() => setRequestKey((current) => current + 1)}
          />
        )}
      </section>
    </div>
  );
}
