import { ArrowUpRight, Clock3, MapPin } from "lucide-react";
import { Link } from "react-router";

import { categoryLabels, formatEventDate, formatEventTimeRange } from "../../lib/eventPresentation";
import type { EventSummary } from "../../types/event";
import { EventImage } from "./EventImage";

interface EventRowProps {
  readonly event: EventSummary;
}

export function EventRow({ event }: EventRowProps) {
  return (
    <article>
      <Link to={`/events/${event.id}`} className="event-row group" aria-label={`Apri l’evento ${event.title}`}>
        <div className="event-row__image-wrap">
          <EventImage
            imageUrl={event.imageUrl}
            imageAlt={event.imageAlt}
            category={event.category}
            className="size-full object-cover transition-transform duration-300 group-active:scale-[1.02] motion-reduce:transition-none"
          />
          <span className="absolute left-2 top-2 rounded-full bg-canvas/95 px-2 py-1 text-[0.65rem] font-extrabold uppercase tracking-[0.11em] text-ink">
            {categoryLabels[event.category]}
          </span>
        </div>

        <div className="min-w-0 flex-1 py-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-xs font-extrabold uppercase tracking-[0.12em] text-linkr-strong">
              {formatEventDate(event.startsAt)} · {formatEventTimeRange(event.startsAt, event.endsAt)}
            </p>
            {event.demo && (
              <span className="rounded-full bg-linkr-soft px-2 py-1 text-[0.6rem] font-extrabold uppercase tracking-[0.11em] text-linkr-strong">
                Demo
              </span>
            )}
          </div>
          <h3 className="mt-1 text-lg font-extrabold leading-tight tracking-[-0.035em] text-ink sm:text-xl">
            {event.title}
          </h3>
          <p className="mt-2 line-clamp-2 text-sm leading-5 text-muted">{event.venueName}</p>
          <div className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-xs font-semibold text-muted">
            <span className="inline-flex items-center gap-1">
              <MapPin size={14} aria-hidden="true" /> {event.neighbourhood}
            </span>
            <span className="inline-flex items-center gap-1">
              <Clock3 size={14} aria-hidden="true" /> {formatEventTimeRange(event.startsAt, event.endsAt)}
            </span>
          </div>
        </div>

        <span className="icon-button mt-0.5 shrink-0" aria-hidden="true">
          <ArrowUpRight size={19} />
        </span>
      </Link>
    </article>
  );
}
