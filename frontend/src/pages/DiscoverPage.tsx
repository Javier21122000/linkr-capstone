import { ArrowDown, Sparkles } from "lucide-react";
import { useMemo, useState } from "react";

import { EventRow } from "../components/events/EventRow";
import { FeaturedEvent } from "../components/events/FeaturedEvent";
import { FilterChip } from "../components/ui/FilterChip";
import { demoEvents, discoverFilters } from "../data/demoEvents";
import type { DiscoverFilter } from "../types/event";

function eventMatchesFilter(filter: DiscoverFilter, event: (typeof demoEvents)[number]) {
  if (filter === "All") return true;
  if (filter === "Tonight") return event.timing === "tonight";
  return event.category === filter;
}

export function DiscoverPage() {
  const [activeFilter, setActiveFilter] = useState<DiscoverFilter>("All");
  const [savedEventIds, setSavedEventIds] = useState<ReadonlySet<string>>(() => new Set());

  const filteredEvents = useMemo(
    () => demoEvents.filter((event) => eventMatchesFilter(activeFilter, event)),
    [activeFilter],
  );

  const featuredEvent = filteredEvents[0];
  const nearbyEvents = filteredEvents.slice(1);

  function toggleSaved(eventId: string) {
    setSavedEventIds((current) => {
      const next = new Set(current);
      if (next.has(eventId)) next.delete(eventId);
      else next.add(eventId);
      return next;
    });
  }

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

        <div className="mt-8 flex items-center gap-2 overflow-x-auto pb-2 scrollbar-none sm:mt-10" aria-label="Filter demo events">
          {discoverFilters.map((filter) => (
            <FilterChip
              key={filter}
              label={filter}
              selected={activeFilter === filter}
              onSelect={() => setActiveFilter(filter)}
            />
          ))}
        </div>

        <div className="mt-4 flex items-start gap-2.5 rounded-2xl border border-linkr/20 bg-linkr-soft px-4 py-3 text-sm text-ink sm:max-w-max">
          <Sparkles className="mt-0.5 shrink-0 text-linkr-strong" size={16} aria-hidden="true" />
          <p>
            <strong>Demo preview.</strong> Events, venues, attendance counts and initials are sample data.
          </p>
        </div>
      </section>

      <section className="page-shell" aria-label={`${activeFilter} demo events`}>
        <p className="sr-only" aria-live="polite">
          {filteredEvents.length} demo events shown for {activeFilter}
        </p>

        {featuredEvent ? (
          <div className="discover-layout">
            <FeaturedEvent
              event={featuredEvent}
              saved={savedEventIds.has(featuredEvent.id)}
              onToggleSaved={toggleSaved}
            />

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
                  {nearbyEvents.map((event) => (
                    <EventRow
                      key={event.id}
                      event={event}
                      saved={savedEventIds.has(event.id)}
                      onToggleSaved={toggleSaved}
                    />
                  ))}
                </div>
              ) : (
                <div className="py-10">
                  <p className="text-lg font-bold text-ink">One strong match for this filter.</p>
                  <p className="mt-2 text-sm leading-6 text-muted">
                    Choose another category to explore more of the demo programme.
                  </p>
                </div>
              )}
            </aside>
          </div>
        ) : (
          <div className="rounded-[1.75rem] border border-line bg-surface px-6 py-16 text-center">
            <h2 className="text-2xl font-extrabold tracking-[-0.04em]">No demo events in this view</h2>
            <button className="button-primary mt-6" type="button" onClick={() => setActiveFilter("All")}>
              Show all events
            </button>
          </div>
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
