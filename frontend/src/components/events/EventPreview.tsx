import { ArrowUpRight, CalendarDays, Clock3, MapPin, X } from "lucide-react";
import { Link } from "react-router";

import { formatEventDate, formatEventTimeRange } from "../../lib/eventPresentation";
import type { EventSummary } from "../../types/event";
import { EventImage } from "./EventImage";

interface EventPreviewProps {
  readonly event: EventSummary;
  readonly onClose: () => void;
}

export function EventPreview({ event, onClose }: EventPreviewProps) {
  return (
    <article className="event-preview content-reveal" aria-labelledby="selected-event-title">
      <div className="event-preview__image">
        <EventImage imageUrl={event.imageUrl} imageAlt={event.imageAlt} category={event.category} className="size-full object-cover" />
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.68rem] font-extrabold uppercase tracking-[0.1em] text-linkr-strong">
          <span className="inline-flex items-center gap-1"><CalendarDays size={13} aria-hidden="true" /> {formatEventDate(event.startsAt)}</span>
          <span className="inline-flex items-center gap-1"><Clock3 size={13} aria-hidden="true" /> {formatEventTimeRange(event.startsAt, event.endsAt)}</span>
        </div>
        <h2 id="selected-event-title" className="mt-1 truncate text-base font-extrabold tracking-[-0.03em] text-ink sm:text-lg">
          {event.title}
        </h2>
        <p className="mt-1 flex items-center gap-1 truncate text-xs font-semibold text-muted">
          <MapPin size={13} className="shrink-0" aria-hidden="true" /> {event.venueName} · {event.neighbourhood}
        </p>
        <Link className="event-preview__link" to={`/events/${event.id}`}>
          Apri evento <ArrowUpRight size={16} aria-hidden="true" />
        </Link>
      </div>

      <button className="event-preview__close" type="button" onClick={onClose} aria-label="Chiudi anteprima evento">
        <X size={17} aria-hidden="true" />
      </button>
    </article>
  );
}
