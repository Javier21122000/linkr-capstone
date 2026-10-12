import { Bookmark, Clock3, MapPin } from "lucide-react";

import type { DemoEvent } from "../../types/event";
import { AttendeeStack } from "./AttendeeStack";

interface EventRowProps {
  readonly event: DemoEvent;
  readonly saved: boolean;
  readonly onToggleSaved: (eventId: string) => void;
}

export function EventRow({ event, saved, onToggleSaved }: EventRowProps) {
  return (
    <article className="event-row">
      <div className="event-row__image-wrap">
        <img
          src={event.imageUrl}
          alt={event.imageAlt}
          className="size-full object-cover transition-transform duration-300 motion-reduce:transition-none"
          loading="lazy"
        />
        <span className="absolute left-2 top-2 rounded-full bg-canvas/95 px-2 py-1 text-[0.65rem] font-extrabold uppercase tracking-[0.11em] text-ink">
          {event.category}
        </span>
      </div>

      <div className="min-w-0 flex-1 py-1">
        <p className="text-xs font-extrabold uppercase tracking-[0.12em] text-linkr-strong">
          {event.dateLabel} · {event.timeLabel}
        </p>
        <h3 className="mt-1 text-lg font-extrabold leading-tight tracking-[-0.035em] text-ink sm:text-xl">
          {event.title}
        </h3>
        <p className="mt-2 line-clamp-2 text-sm leading-5 text-muted">{event.description}</p>
        <div className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-xs font-semibold text-muted">
          <span className="inline-flex items-center gap-1">
            <MapPin size={14} aria-hidden="true" /> {event.neighbourhood}
          </span>
          <span className="inline-flex items-center gap-1">
            <Clock3 size={14} aria-hidden="true" /> {event.distanceLabel}
          </span>
        </div>
        <div className="mt-3">
          <AttendeeStack initials={event.attendeeInitials} count={event.attendeeCount} />
        </div>
      </div>

      <button
        type="button"
        className="icon-button mt-0.5 shrink-0"
        aria-label={saved ? `Remove ${event.title} from saved events` : `Save ${event.title}`}
        aria-pressed={saved}
        onClick={() => onToggleSaved(event.id)}
      >
        <Bookmark className={saved ? "fill-current text-linkr-strong" : ""} size={19} aria-hidden="true" />
      </button>
    </article>
  );
}
