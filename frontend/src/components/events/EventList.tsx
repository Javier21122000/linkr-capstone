import { LocateFixed, MapPin, RotateCcw, X } from "lucide-react";
import { useEffect, useRef } from "react";

import { categoryLabels, formatEventDate, formatEventTimeRange } from "../../lib/eventPresentation";
import { hasValidCoordinates } from "../../lib/map";
import type { EventSummary } from "../../types/event";
import { EmptyEventsIllustration } from "../ui/EmptyEventsIllustration";
import { EmptyState } from "../ui/EmptyState";
import { IconButton } from "../ui/IconButton";
import { CategoryDisc } from "./CategoryIcon";
import { EventImage } from "./EventImage";

interface EventListProps {
  readonly events: readonly EventSummary[];
  readonly selectedEventId: string | null;
  /** `sheet`: pannello apribile (dialog) su mobile/tablet. `panel`: colonna fissa su desktop. */
  readonly variant: "sheet" | "panel";
  readonly autoScrollToSelection: boolean;
  readonly loading: boolean;
  readonly errorMessage?: string;
  /** Nome della categoria attiva, mostrato solo se diversa da "Tutte". */
  readonly activeCategoryLabel?: string;
  readonly onSelectEvent: (eventId: string) => void;
  readonly onClose?: () => void;
  readonly onRetry: () => void;
  /** Se presente, lo stato vuoto propone di azzerare il filtro. */
  readonly onShowAll?: () => void;
}

export function EventList({
  events,
  selectedEventId,
  variant,
  autoScrollToSelection,
  loading,
  errorMessage,
  activeCategoryLabel,
  onSelectEvent,
  onClose,
  onRetry,
  onShowAll,
}: EventListProps) {
  const isSheet = variant === "sheet";
  const panelRef = useRef<HTMLElement>(null);
  const listBodyRef = useRef<HTMLDivElement>(null);
  const previousSelectedEventIdRef = useRef(selectedEventId);

  useEffect(() => {
    if (!isSheet || !onClose) return;
    panelRef.current?.focus({ preventScroll: true });

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose?.();
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isSheet, onClose]);

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
      className={`event-list-panel event-list-panel--${variant} ${isSheet ? "glass glass--strong" : ""} content-reveal`}
      role={isSheet ? "dialog" : "region"}
      tabIndex={isSheet ? -1 : undefined}
      aria-labelledby="event-list-title"
    >
      <div className="event-list-panel__header">
        <div className="min-w-0">
          <p className="section-kicker">Discover Milano</p>
          <h2 id="event-list-title" className="mt-1 text-2xl font-extrabold tracking-[-0.045em] text-ink">
            {loading ? "Cerco eventi" : errorMessage ? "Eventi" : `${events.length} ${events.length === 1 ? "evento" : "eventi"}`}
          </h2>
          {activeCategoryLabel && !loading && !errorMessage && (
            <p className="mt-0.5 text-sm font-bold text-muted">Categoria: {activeCategoryLabel}</p>
          )}
        </div>
        {isSheet && onClose && (
          <IconButton className="shrink-0" label="Torna alla mappa" onClick={onClose}>
            <X size={19} aria-hidden="true" />
          </IconButton>
        )}
      </div>

      <div ref={listBodyRef} className="event-list-panel__body">
        {loading && (
          <div className="space-y-3 pt-3" aria-busy="true" aria-label="Caricamento eventi">
            {Array.from({ length: 4 }, (_, index) => <div className="h-24 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" key={index} />)}
          </div>
        )}

        {!loading && errorMessage && (
          <div className="event-list-panel__message">
            <EmptyState
              title="Non riusciamo a caricare gli eventi"
              description={errorMessage}
              action={(
                <button className="button-primary inline-flex items-center gap-2" type="button" onClick={onRetry}>
                  <RotateCcw size={17} aria-hidden="true" /> Riprova
                </button>
              )}
            />
          </div>
        )}

        {!loading && !errorMessage && events.length === 0 && (
          <div className="event-list-panel__message">
            <EmptyState
              illustration={<EmptyEventsIllustration />}
              title="Nessun evento vicino"
              description="La città è sempre in movimento: prova un’altra categoria."
              action={onShowAll && (
                <button className="button-secondary inline-flex items-center" type="button" onClick={onShowAll}>
                  Mostra tutti gli eventi
                </button>
              )}
            />
          </div>
        )}

        {!loading && !errorMessage && events.map((event) => {
          const locationAvailable = hasValidCoordinates(event);
          const selected = selectedEventId === event.id;
          return (
            <button
              key={event.id}
              className="event-list-item"
              type="button"
              data-selected={selected}
              aria-pressed={selected}
              onClick={() => onSelectEvent(event.id)}
            >
              <span className="event-list-item__image">
                <EventImage imageUrl={event.imageUrl} imageAlt="" category={event.category} compact className="size-full object-cover" />
              </span>
              <span className="min-w-0 flex-1 text-left">
                <span className="block text-xs font-extrabold uppercase tracking-[0.07em] text-linkr-strong">
                  {formatEventDate(event.startsAt)} · {formatEventTimeRange(event.startsAt, event.endsAt)}
                </span>
                <span className="mt-1 block truncate text-base font-extrabold tracking-[-0.025em] text-ink">{event.title}</span>
                <span className="mt-1 flex items-center gap-1 truncate text-[0.8125rem] font-semibold text-muted">
                  <MapPin size={13} className="shrink-0" aria-hidden="true" />
                  <span className="truncate">{locationAvailable ? `${event.venueName} · ${event.neighbourhood}` : "Posizione non disponibile"}</span>
                </span>
                <span className="mt-2 flex items-center gap-1.5 text-xs font-extrabold text-muted">
                  <CategoryDisc category={event.category} size={12} className="category-disc--sm" />
                  {categoryLabels[event.category]}
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
