import { ArrowDown, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";

import { EventRow } from "../components/events/EventRow";
import { FeaturedEvent } from "../components/events/FeaturedEvent";
import { FilterChip } from "../components/ui/FilterChip";
import { categoryOptions } from "../lib/eventPresentation";
import { getEvents, getEventsErrorMessage, isRequestCancelled } from "../lib/events";
import type { EventCategory, EventSummary } from "../types/event";

type EventsState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly events: readonly EventSummary[] }
  | { readonly status: "error"; readonly message: string };

function EventsLoadingState() {
  return (
    <div className="discover-layout" aria-busy="true" aria-label="Caricamento eventi">
      <div className="min-h-[24rem] animate-pulse rounded-[1.75rem] bg-line/70 motion-reduce:animate-none sm:min-h-[30rem] lg:min-h-[38rem]" />
      <div className="space-y-5" aria-hidden="true">
        <div className="h-16 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
        {Array.from({ length: 3 }, (_, index) => (
          <div key={index} className="flex gap-4 border-t border-line pt-5">
            <div className="h-32 w-24 shrink-0 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
            <div className="flex-1 space-y-3 py-2">
              <div className="h-3 w-2/5 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
              <div className="h-6 w-4/5 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
              <div className="h-4 w-3/5 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

interface EventsMessageProps {
  readonly title: string;
  readonly description: string;
  readonly actionLabel?: string;
  readonly onAction?: () => void;
}

function EventsMessage({ title, description, actionLabel, onAction }: EventsMessageProps) {
  return (
    <div className="rounded-[1.75rem] border border-line bg-surface px-6 py-16 text-center">
      <h2 className="text-2xl font-extrabold tracking-[-0.04em] text-ink">{title}</h2>
      <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-muted">{description}</p>
      {actionLabel && onAction && (
        <button className="button-primary mt-6 inline-flex items-center gap-2" type="button" onClick={onAction}>
          <RotateCcw size={17} aria-hidden="true" />
          {actionLabel}
        </button>
      )}
    </div>
  );
}

export function DiscoverPage() {
  const [activeCategory, setActiveCategory] = useState<EventCategory | null>(null);
  const [requestKey, setRequestKey] = useState(0);
  const [eventsState, setEventsState] = useState<EventsState>({ status: "loading" });

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

  const selectedLabel = categoryOptions.find((option) => option.value === activeCategory)?.label ?? "Tutte";
  const events = eventsState.status === "success" ? eventsState.events : [];
  const featuredEvent = events[0];
  const nearbyEvents = events.slice(1);

  return (
    <div className="pb-32 md:pb-20">
      <section className="page-shell pb-6 pt-10 sm:pt-14 lg:pb-8 lg:pt-20" aria-labelledby="discover-title">
        <div className="grid items-end gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <div>
            <p className="section-kicker">Discover Milano</p>
            <h1 id="discover-title" className="display-title mt-3 max-w-4xl">
              Your next good story starts <span className="text-linkr-strong">out there.</span>
            </h1>
          </div>
          <p className="max-w-md text-base leading-7 text-muted lg:pb-2">
            Curated events, real places and an easier way to find people who are already going.
          </p>
        </div>

        <div className="mt-8 flex items-center gap-2 overflow-x-auto pb-2 scrollbar-none sm:mt-10" aria-label="Filtra gli eventi per categoria">
          {categoryOptions.map((option) => (
            <FilterChip
              key={option.value ?? "all"}
              label={option.label}
              selected={activeCategory === option.value}
              onSelect={() => setActiveCategory(option.value)}
            />
          ))}
        </div>
      </section>

      <section className="page-shell" aria-label={`Eventi: ${selectedLabel}`}>
        <p className="sr-only" aria-live="polite">
          {eventsState.status === "loading" && "Caricamento eventi in corso"}
          {eventsState.status === "error" && "Impossibile caricare gli eventi"}
          {eventsState.status === "success" && `${events.length} eventi mostrati per la categoria ${selectedLabel}`}
        </p>

        {eventsState.status === "loading" && <EventsLoadingState />}

        {eventsState.status === "error" && (
          <EventsMessage
            title="Non riusciamo a caricare gli eventi"
            description={eventsState.message}
            actionLabel="Riprova"
            onAction={() => setRequestKey((current) => current + 1)}
          />
        )}

        {eventsState.status === "success" && featuredEvent && (
          <div className="discover-layout">
            <FeaturedEvent event={featuredEvent} />

            <aside className="nearby-panel" aria-labelledby="nearby-title">
              <div className="flex items-end justify-between gap-4 border-b border-line pb-5">
                <div>
                  <p className="section-kicker">Close to you</p>
                  <h2 id="nearby-title" className="mt-1 text-2xl font-extrabold tracking-[-0.045em] text-ink sm:text-3xl">
                    Worth leaving home for
                  </h2>
                </div>
                <ArrowDown className="hidden text-linkr-strong sm:block lg:hidden" aria-hidden="true" />
              </div>

              {nearbyEvents.length > 0 ? (
                <div className="divide-y divide-line">
                  {nearbyEvents.map((event) => <EventRow key={event.id} event={event} />)}
                </div>
              ) : (
                <div className="py-10">
                  <p className="text-lg font-bold text-ink">Un solo evento in questa categoria.</p>
                  <p className="mt-2 text-sm leading-6 text-muted">Scegli un’altra categoria per esplorare altri eventi.</p>
                </div>
              )}
            </aside>
          </div>
        )}

        {eventsState.status === "success" && events.length === 0 && (
          <EventsMessage
            title="Nessun evento disponibile"
            description={activeCategory ? `Non ci sono eventi nella categoria ${selectedLabel}.` : "Non ci sono ancora eventi da mostrare."}
            actionLabel={activeCategory ? "Mostra tutte le categorie" : undefined}
            onAction={activeCategory ? () => setActiveCategory(null) : undefined}
          />
        )}
      </section>

      <section className="page-shell mt-10 sm:mt-14" aria-labelledby="map-preview-title">
        <div className="map-preview-band">
          <div>
            <p className="section-kicker section-kicker--light">The city, at a glance</p>
            <h2 id="map-preview-title" className="mt-2 max-w-2xl text-3xl font-extrabold leading-[1.05] tracking-[-0.05em] text-white sm:text-5xl">
              Discover stays editorial. Map becomes spatial.
            </h2>
          </div>
          <div className="max-w-md lg:text-right">
            <p className="text-sm leading-6 text-white/72 sm:text-base">
              The Map experience is intentionally separate and arrives next—event markers, filters and a responsive result panel, without shortcuts or fake maps.
            </p>
            <span className="mt-5 inline-flex rounded-full border border-white/22 px-4 py-2 text-xs font-extrabold uppercase tracking-[0.13em] text-white">
              Map architecture reserved
            </span>
          </div>
        </div>
      </section>
    </div>
  );
}
