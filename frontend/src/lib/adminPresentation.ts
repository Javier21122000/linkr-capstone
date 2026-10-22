import type { EventCategory, EventSummary } from "../types/event";

export type AdminEventSourceFilter = "ALL" | "TICKETMASTER" | "LINKR";

export interface AdminEventCounts {
  readonly total: number;
  readonly ticketmaster: number;
  readonly community: number;
  readonly demo: number;
}

export function countAdminEvents(events: readonly EventSummary[]): AdminEventCounts {
  return events.reduce<AdminEventCounts>((counts, event) => ({
    total: counts.total + 1,
    ticketmaster: counts.ticketmaster + (event.source?.provider === "TICKETMASTER" ? 1 : 0),
    community: counts.community + (event.type === "COMMUNITY" ? 1 : 0),
    demo: counts.demo + (event.demo ? 1 : 0),
  }), { total: 0, ticketmaster: 0, community: 0, demo: 0 });
}

export function filterAdminEvents(
  events: readonly EventSummary[],
  search: string,
  source: AdminEventSourceFilter,
  category: EventCategory | "ALL",
): EventSummary[] {
  const term = search.trim().toLocaleLowerCase("it");
  return events.filter((event) => {
    const matchesSearch = !term || `${event.title} ${event.venueName}`.toLocaleLowerCase("it").includes(term);
    const isTicketmaster = event.source?.provider === "TICKETMASTER";
    const matchesSource = source === "ALL" || (source === "TICKETMASTER" ? isTicketmaster : !isTicketmaster);
    const matchesCategory = category === "ALL" || event.category === category;
    return matchesSearch && matchesSource && matchesCategory;
  });
}
