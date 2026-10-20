import type { EventCategory, EventCategoryOption } from "../types/event";

const ROME_TIME_ZONE = "Europe/Rome";
const DEFAULT_EVENT_DURATION_MS = 3 * 60 * 60 * 1_000;

export const categoryLabels: Record<EventCategory, string> = {
  MUSIC: "Musica",
  FOOD: "Cibo",
  CULTURE: "Cultura",
  OUTDOORS: "All’aperto",
  NIGHTLIFE: "Vita notturna",
  SPORT: "Sport",
  SOCIAL: "Socialità",
};

export const categoryColors: Record<EventCategory, string> = {
  MUSIC: "#8657D8",
  FOOD: "#E59A48",
  CULTURE: "#CF709A",
  OUTDOORS: "#579F76",
  NIGHTLIFE: "#4355BD",
  SPORT: "#428CB5",
  SOCIAL: "#E76A56",
};

export const unknownCategoryColor = "#747872";

export const categoryOptions: readonly EventCategoryOption[] = [
  { value: null, label: "Tutte" },
  ...Object.entries(categoryLabels).map(([value, label]) => ({
    value: value as EventCategory,
    label,
  })),
];

const dateFormatter = new Intl.DateTimeFormat("it-IT", {
  timeZone: ROME_TIME_ZONE,
  weekday: "short",
  day: "numeric",
  month: "short",
});

const timeFormatter = new Intl.DateTimeFormat("it-IT", {
  timeZone: ROME_TIME_ZONE,
  hour: "2-digit",
  minute: "2-digit",
});

const dateKeyFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: ROME_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

export function formatEventDate(isoDate: string): string {
  return dateFormatter.format(new Date(isoDate));
}

export function formatEventTime(isoDate: string): string {
  return timeFormatter.format(new Date(isoDate));
}

export function formatEventTimeRange(startsAt: string, endsAt?: string | null): string {
  const startTime = formatEventTime(startsAt);
  if (!endsAt) return startTime;

  const sameDay = dateKeyFormatter.format(new Date(startsAt)) === dateKeyFormatter.format(new Date(endsAt));
  if (sameDay) return `${startTime}–${formatEventTime(endsAt)}`;

  return `${startTime} – ${formatEventDate(endsAt)} ${formatEventTime(endsAt)}`;
}

export function isEventEnded(startsAt: string, endsAt?: string | null, now = Date.now()): boolean {
  const startsAtTime = Date.parse(startsAt);
  const endsAtTime = endsAt ? Date.parse(endsAt) : startsAtTime + DEFAULT_EVENT_DURATION_MS;
  return Number.isFinite(endsAtTime) && now >= endsAtTime;
}
