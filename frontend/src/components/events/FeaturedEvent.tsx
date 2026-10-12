import { Bookmark, CalendarDays, Clock3, MapPin } from "lucide-react";

import type { DemoEvent } from "../../types/event";
import { AttendeeStack } from "./AttendeeStack";

interface FeaturedEventProps {
  readonly event: DemoEvent;
  readonly saved: boolean;
  readonly onToggleSaved: (eventId: string) => void;
}

export function FeaturedEvent({ event, saved, onToggleSaved }: FeaturedEventProps) {
  return (
    <article className="featured-event">
      <div className="relative min-h-[24rem] overflow-hidden rounded-[1.75rem] bg-ink sm:min-h-[30rem] lg:min-h-[38rem]">
        <img
          src={event.imageUrl}
          alt={event.imageAlt}
          className="absolute inset-0 size-full object-cover"
          fetchPriority="high"
        />
        <div className="featured-event__shade" aria-hidden="true" />

        <div className="absolute inset-x-0 top-0 flex items-start justify-between p-4 sm:p-6">
          <span className="rounded-full bg-canvas px-3 py-2 text-xs font-extrabold uppercase tracking-[0.13em] text-ink">
            {event.eyebrow}
          </span>
          <button
            type="button"
            className="icon-button icon-button--dark"
            aria-label={saved ? `Remove ${event.title} from saved events` : `Save ${event.title}`}
            aria-pressed={saved}
            onClick={() => onToggleSaved(event.id)}
          >
            <Bookmark className={saved ? "fill-current" : ""} aria-hidden="true" size={20} />
          </button>
        </div>

        <div className="absolute inset-x-0 bottom-0 p-5 text-white sm:p-8">
          <p className="mb-2 text-sm font-bold text-white/76">{event.category} · {event.neighbourhood}</p>
          <h2 className="max-w-xl text-[clamp(2rem,8vw,4.5rem)] font-extrabold leading-[0.95] tracking-[-0.055em]">
            {event.title}
          </h2>
          <p className="mt-4 max-w-xl text-sm leading-6 text-white/82 sm:text-base">
            {event.description}
          </p>
          <div className="mt-5 flex flex-wrap gap-x-4 gap-y-2 text-sm font-semibold text-white/88">
            <span className="inline-flex items-center gap-1.5">
              <CalendarDays size={17} aria-hidden="true" /> {event.dateLabel}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <Clock3 size={17} aria-hidden="true" /> {event.timeLabel}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <MapPin size={17} aria-hidden="true" /> {event.distanceLabel}
            </span>
          </div>
          <div className="mt-5 border-t border-white/18 pt-4">
            <AttendeeStack
              initials={event.attendeeInitials}
              count={event.attendeeCount}
              inverse
            />
          </div>
        </div>
      </div>
    </article>
  );
}
