import type { EventCategory, EventCategoryOption } from "../types/event";

const ROME_TIME_ZONE = "Europe/Rome";

export const categoryLabels: Record<EventCategory, string> = {
  MUSIC: "Musica",
  FOOD: "Cibo",
  CULTURE: "Cultura",
  OUTDOORS: "All’aperto",
  NIGHTLIFE: "Vita notturna",
  SPORT: "Sport",
  SOCIAL: "Socialità",
};

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
