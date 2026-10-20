import { List, LocateFixed, RotateCcw, X } from "lucide-react";
import { useEffect, useRef } from "react";

import { formatEventDate, formatEventTimeRange } from "../../lib/eventPresentation";
import { hasValidCoordinates } from "../../lib/map";
import type { EventSummary } from "../../types/event";
import { EventImage } from "./EventImage";

interface EventListProps {
  readonly events: readonly EventSummary[];
  readonly selectedEventId: string | null;
  readonly autoScrollToSelection: boolean;
  readonly loading: boolean;
  readonly errorMessage?: string;
  readonly onSelectEvent: (eventId: string) => void;
  readonly onClose: () => void;
  readonly onRetry: () => void;
}

export function EventList({
  events,
  selectedEventId,
  autoScrollToSelection,
  loading,
  errorMessage,
  onSelectEvent,
  onClose,
  onRetry,
}: EventListProps) {
  const panelRef = useRef<HTMLElement>(null);
  const listBodyRef = useRef<HTMLDivElement>(null);
  const previousSelectedEventIdRef = useRef(selectedEventId);

  useEffect(() => {
    panelRef.current?.focus({ preventScroll: true });

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    const previousSelectedEventId = previousSelectedEventIdRef.current;
    previousSelectedEventIdRef.current = selectedEventId;

    if (!autoScrollToSelection || !selectedEventId || selectedEventId === previousSelectedEventId) return;

    const listBody = listBodyRef.current;
    const selectedItem = listBody?.querySelector<HTMLElement>('.event-list-item[data-selected="true"]');
    if (!listBody || !selectedItem) return;

    const listBounds = listBody.getBoundingClientRect();
    const itemBounds = selectedItem.getBoundingClientRect();
    const overflow = itemBounds.top < listBounds.top
      ? itemBounds.top - listBounds.top
      : Math.max(0, itemBounds.bottom - listBounds.bottom);

    if (overflow === 0) return;

    listBody.scrollTo({
      top: listBody.scrollTop + overflow,
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
    });
  }, [autoScrollToSelection, selectedEventId]);

  return (
    <section
      ref={panelRef}
      className="event-list-panel content-reveal"
      role="dialog"
      tabIndex={-1}
      aria-labelledby="event-list-title"
    >
      <div className="event-list-panel__header">
        <div>
          <p className="section-kicker">Discover Milano</p>
          <h2 id="event-list-title" className="mt-1 text-2xl font-extrabold tracking-[-0.045em] text-ink">
            {loading ? "Cerco eventi" : `${events.length} ${events.length === 1 ? "evento" : "eventi"}`}
          </h2>
        </div>
        <button className="icon-button shrink-0" type="button" onClick={onClose} aria-label="Torna alla mappa">
          <X size={19} aria-hidden="true" />
        </button>
      </div>

      <div ref={listBodyRef} className="event-list-panel__body">
        {loading && (
          <div className="space-y-3" aria-busy="true" aria-label="Caricamento eventi">
            {Array.from({ length: 4 }, (_, index) => <div className="h-24 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" key={index} />)}
          </div>
        )}

        {!loading && errorMessage && (
          <div className="event-list-panel__message">
            <p className="text-lg font-extrabold text-ink">Non riusciamo a caricare gli eventi</p>
            <p className="mt-2 text-sm leading-6 text-muted">{errorMessage}</p>
            <button className="button-primary mt-5 inline-flex items-center gap-2" type="button" onClick={onRetry}>
              <RotateCcw size={17} aria-hidden="true" /> Riprova
            </button>
          </div>
        )}

        {!loading && !errorMessage && events.length === 0 && (
          <div className="event-list-panel__message">
            <List className="mx-auto text-linkr-strong" size={28} aria-hidden="true" />
            <p className="mt-3 text-lg font-extrabold text-ink">Nessun evento disponibile</p>
            <p className="mt-2 text-sm leading-6 text-muted">Prova un’altra categoria.</p>
          </div>
        )}

        {!loading && !errorMessage && events.map((event) => {
          const locationAvailable = hasValidCoordinates(event);
          return (
            <button
              key={event.id}
              className="event-list-item"
              type="button"
              data-selected={selectedEventId === event.id}
              aria-pressed={selectedEventId === event.id}
              onClick={() => onSelectEvent(event.id)}
            >
              <span className="event-list-item__image">
                <EventImage imageUrl={event.imageUrl} imageAlt="" category={event.category} className="size-full object-cover" />
              </span>
              <span className="min-w-0 flex-1 text-left">
                <span className="block text-[0.68rem] font-extrabold uppercase tracking-[0.1em] text-linkr-strong">
                  {formatEventDate(event.startsAt)} · {formatEventTimeRange(event.startsAt, event.endsAt)}
                </span>
                <span className="mt-1 block truncate text-base font-extrabold tracking-[-0.025em] text-ink">{event.title}</span>
                <span className="mt-1 block truncate text-xs font-semibold text-muted">
                  {locationAvailable ? `${event.venueName} · ${event.neighbourhood}` : "Posizione non disponibile"}
                </span>
              </span>
              <LocateFixed className="shrink-0 text-muted" size={18} aria-hidden="true" />
            </button>
          );
        })}
      </div>
    </section>
  );
}
