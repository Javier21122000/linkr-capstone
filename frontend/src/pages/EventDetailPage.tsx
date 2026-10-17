import { ArrowLeft, CalendarDays, Clock3, MapPin, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";

import { EventImage } from "../components/events/EventImage";
import { EventParticipationPanel } from "../components/events/EventParticipationPanel";
import { categoryLabels, formatEventDate, formatEventTimeRange } from "../lib/eventPresentation";
import { getEvent, getEventsErrorMessage, isRequestCancelled } from "../lib/events";
import type { EventDetail } from "../types/event";

type EventState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly event: EventDetail }
  | { readonly status: "error"; readonly message: string };

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const [requestKey, setRequestKey] = useState(0);
  const [eventState, setEventState] = useState<EventState>({ status: "loading" });

  useEffect(() => {
    if (!eventId) {
      setEventState({ status: "error", message: "Identificativo evento non valido." });
      return;
    }

    const controller = new AbortController();
    setEventState({ status: "loading" });

    void getEvent(eventId, controller.signal)
      .then((event) => setEventState({ status: "success", event }))
      .catch((error: unknown) => {
        if (!isRequestCancelled(error)) {
          setEventState({ status: "error", message: getEventsErrorMessage(error) });
        }
      });

    return () => controller.abort();
  }, [eventId, requestKey]);

  return (
    <div className="page-shell page-with-mobile-nav pt-8 sm:pt-12 lg:pt-16">
      <Link className="inline-flex min-h-11 items-center gap-2 rounded-full font-extrabold text-ink" to="/">
        <ArrowLeft size={19} aria-hidden="true" /> Torna a Discover
      </Link>

      {eventState.status === "loading" && (
        <div className="mt-7 grid gap-8 lg:grid-cols-[minmax(0,1.15fr)_minmax(20rem,0.85fr)]" aria-busy="true" aria-label="Caricamento dettaglio evento">
          <div className="min-h-[24rem] animate-pulse rounded-[1.75rem] bg-line/70 motion-reduce:animate-none sm:min-h-[36rem]" />
          <div className="space-y-5 py-4" aria-hidden="true">
            <div className="h-4 w-1/3 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
            <div className="h-20 w-full animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
            <div className="h-28 w-full animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
          </div>
        </div>
      )}

      {eventState.status === "error" && (
        <div className="mt-7 rounded-[1.75rem] border border-line bg-surface px-6 py-16 text-center">
          <h1 className="text-3xl font-extrabold tracking-[-0.045em] text-ink">Evento non disponibile</h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-muted">{eventState.message}</p>
          <button className="button-primary mt-6 inline-flex items-center gap-2" type="button" onClick={() => setRequestKey((current) => current + 1)}>
            <RotateCcw size={17} aria-hidden="true" /> Riprova
          </button>
        </div>
      )}

      {eventState.status === "success" && (
        <article className="mt-7 grid gap-8 lg:grid-cols-[minmax(0,1.15fr)_minmax(20rem,0.85fr)] lg:gap-12">
          <figure className="m-0">
            <div className="relative min-h-[24rem] overflow-hidden rounded-[1.75rem] bg-line sm:min-h-[36rem] lg:min-h-[42rem]">
              <EventImage
                imageUrl={eventState.event.imageUrl}
                imageAlt={eventState.event.imageAlt}
                category={eventState.event.category}
                className="absolute inset-0 size-full object-cover"
                loading="eager"
              />
            </div>
            {eventState.event.imageCredit && (
              <figcaption className="mt-2 text-xs leading-5 text-muted">Crediti immagine: {eventState.event.imageCredit}</figcaption>
            )}
          </figure>

          <div className="self-center">
            <div className="flex flex-wrap items-center gap-2">
              <span className="section-kicker">{categoryLabels[eventState.event.category]}</span>
              {eventState.event.demo && (
                <span className="rounded-full bg-linkr-soft px-2.5 py-1 text-[0.65rem] font-extrabold uppercase tracking-[0.12em] text-linkr-strong">Demo</span>
              )}
            </div>
            <h1 className="mt-4 text-[clamp(2.5rem,7vw,5.5rem)] font-extrabold leading-[0.94] tracking-[-0.06em] text-ink">
              {eventState.event.title}
            </h1>

            <div className="mt-7 grid gap-3 border-y border-line py-5 text-sm font-bold text-ink sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
              <span className="inline-flex items-center gap-2">
                <CalendarDays className="text-linkr-strong" size={18} aria-hidden="true" /> {formatEventDate(eventState.event.startsAt)}
              </span>
              <span className="inline-flex items-center gap-2">
                <Clock3 className="text-linkr-strong" size={18} aria-hidden="true" /> {formatEventTimeRange(eventState.event.startsAt, eventState.event.endsAt)}
              </span>
              <span className="inline-flex items-start gap-2 sm:col-span-2 lg:col-span-1 xl:col-span-2">
                <MapPin className="mt-0.5 shrink-0 text-linkr-strong" size={18} aria-hidden="true" />
                <span>{eventState.event.venueName} · {eventState.event.address}, {eventState.event.neighbourhood}</span>
              </span>
            </div>

            <section className="mt-7" aria-labelledby="event-description-title">
              <h2 id="event-description-title" className="text-sm font-extrabold uppercase tracking-[0.13em] text-muted">Informazioni sull’evento</h2>
              <p className="mt-3 whitespace-pre-line text-base leading-7 text-ink">{eventState.event.description}</p>
            </section>

            <EventParticipationPanel
              eventId={eventState.event.id}
              startsAt={eventState.event.startsAt}
              endsAt={eventState.event.endsAt}
            />
          </div>
        </article>
      )}
    </div>
  );
}
