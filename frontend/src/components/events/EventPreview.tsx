import { ArrowUpRight, MapPin, X } from "lucide-react";
import { Link } from "react-router";

import { categoryLabels, formatEventDate, formatEventTimeRange } from "../../lib/eventPresentation";
import type { EventSummary } from "../../types/event";
import { CategoryDisc } from "./CategoryIcon";
import { EventImage } from "./EventImage";

interface EventPreviewProps {
  readonly event: EventSummary;
  readonly onClose: () => void;
}

export function EventPreview({ event, onClose }: EventPreviewProps) {
  return (
    <article className="event-preview glass glass--strong content-reveal" aria-labelledby="selected-event-title">
      <div className="event-preview__image">
        <EventImage imageUrl={event.imageUrl} imageAlt={event.imageAlt} category={event.category} compact className="size-full object-cover" />
      </div>

      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 text-xs font-extrabold uppercase tracking-[0.08em] text-muted">
          <CategoryDisc category={event.category} size={12} className="category-disc--sm" />
          {categoryLabels[event.category]}
        </p>
        <h2 id="selected-event-title" className="mt-1 truncate text-base font-extrabold tracking-[-0.03em] text-ink sm:text-lg">
          <Link className="event-preview__link" to={`/events/${event.id}`}>
            {event.title}
          </Link>
        </h2>
        <p className="mt-0.5 truncate text-sm font-extrabold text-linkr-strong">
          {formatEventDate(event.startsAt)} · {formatEventTimeRange(event.startsAt, event.endsAt)}
        </p>
        <p className="mt-0.5 flex items-center gap-1 truncate text-[0.8125rem] font-semibold text-muted">
          <MapPin size={13} className="shrink-0" aria-hidden="true" /> {event.venueName} · {event.neighbourhood}
        </p>
      </div>

      <span className="event-preview__go" aria-hidden="true">
        <ArrowUpRight size={19} strokeWidth={2.4} />
      </span>

      <button className="event-preview__close" type="button" onClick={onClose} aria-label="Chiudi anteprima evento">
        <span className="event-preview__close-disc glass glass--strong">
          <X size={15} strokeWidth={2.6} aria-hidden="true" />
        </span>
      </button>
    </article>
  );
}
