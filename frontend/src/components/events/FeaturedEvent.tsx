import { ArrowUpRight, CalendarDays, Clock3, MapPin } from "lucide-react";
import { Link } from "react-router";

import { categoryLabels, formatEventDate, formatEventTimeRange } from "../../lib/eventPresentation";
import type { EventSummary } from "../../types/event";
import { EventImage } from "./EventImage";

interface FeaturedEventProps {
  readonly event: EventSummary;
}

export function FeaturedEvent({ event }: FeaturedEventProps) {
  return (
    <article className="featured-event">
      <Link
        to={`/events/${event.id}`}
        className="group relative block min-h-[24rem] overflow-hidden rounded-[1.75rem] bg-ink sm:min-h-[30rem] lg:min-h-[38rem]"
        aria-label={`Apri l’evento ${event.title}`}
      >
        <EventImage
          imageUrl={event.imageUrl}
          imageAlt={event.imageAlt}
          category={event.category}
          className="absolute inset-0 size-full object-cover transition-transform duration-300 group-active:scale-[1.01] motion-reduce:transition-none"
          loading="eager"
        />
        <div className="featured-event__shade" aria-hidden="true" />

        <div className="absolute inset-x-0 top-0 flex items-start justify-between gap-3 p-4 sm:p-6">
          <div className="flex flex-wrap gap-2">
            <span className="rounded-full bg-canvas px-3 py-2 text-xs font-extrabold uppercase tracking-[0.13em] text-ink">
              In evidenza
            </span>
            {event.demo && (
              <span className="rounded-full bg-linkr px-3 py-2 text-xs font-extrabold uppercase tracking-[0.13em] text-white">
                Demo
              </span>
            )}
          </div>
          <span className="icon-button icon-button--dark" aria-hidden="true">
            <ArrowUpRight size={20} />
          </span>
        </div>

        <div className="absolute inset-x-0 bottom-0 p-5 text-white sm:p-8">
          <p className="mb-2 text-sm font-bold text-white/76">
            {categoryLabels[event.category]} · {event.neighbourhood}
          </p>
          <h2 className="max-w-xl text-[clamp(2rem,8vw,4.5rem)] font-extrabold leading-[0.95] tracking-[-0.055em]">
            {event.title}
          </h2>
          <p className="mt-4 text-sm font-semibold text-white/82 sm:text-base">{event.venueName}</p>
          <div className="mt-5 flex flex-wrap gap-x-4 gap-y-2 border-t border-white/18 pt-4 text-sm font-semibold text-white/88">
            <span className="inline-flex items-center gap-1.5">
              <CalendarDays size={17} aria-hidden="true" /> {formatEventDate(event.startsAt)}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <Clock3 size={17} aria-hidden="true" /> {formatEventTimeRange(event.startsAt, event.endsAt)}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <MapPin size={17} aria-hidden="true" /> {event.neighbourhood}
            </span>
          </div>
        </div>
      </Link>
    </article>
  );
}
