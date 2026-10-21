import {
  addDaysToDateKey,
  EVENT_TIME_ZONE,
  isValidDateKey,
  isValidTimeOfDay,
  resolveRomeWallTime,
  romeDateKey,
  weekdayOfDateKey,
} from "./romeTime.ts";

/**
 * Date di Quick Create. Giorno e ora sono sempre quelli di Milano (Europe/Rome), qualunque sia il fuso del dispositivo:
 * "oggi" è il giorno di calendario di Milano e le 20:30 scelte sono le 20:30 di Milano, non quelle dell'orologio di chi scrive.
 * Il risultato è un istante (ISO UTC): è ciò che il backend si aspetta, e non cambia con il fuso del telefono.
 * Le chiavi data sono stringhe `AAAA-MM-GG`; ora legale, ora solare, orari inesistenti e ambigui li risolve `romeTime.ts`.
 */
export type QuickDateChoice = "today" | "tomorrow" | "weekend" | "custom";

export type ScheduleIssue = "date-missing" | "date-invalid" | "time-missing" | "time-invalid" | "nonexistent" | "past";

/** Per l'ora che a Milano capita due volte (cambio all'ora solare): la prima (ora legale) o la seconda (ora solare). */
export type AmbiguousChoice = "first" | "second";

/** Orari proposti come scorciatoie. Solo scorciatoie: l'utente può scegliere qualsiasi altro orario. */
export const SUGGESTED_TIMES: readonly string[] = ["15:00", "19:00", "21:00"];

const longDateFormatter = new Intl.DateTimeFormat("it-IT", { timeZone: "UTC", weekday: "long", day: "numeric", month: "long" });
const shortDateTimeFormatter = new Intl.DateTimeFormat("it-IT", {
  timeZone: EVENT_TIME_ZONE,
  weekday: "short",
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
});

/** Giorno di calendario di Milano adesso. */
export function todayInRome(now: Date): string {
  return romeDateKey(now.getTime());
}

/** Sabato e domenica restano "oggi"; negli altri giorni è il prossimo sabato. */
function weekendDateKey(todayKey: string): string {
  const weekday = weekdayOfDateKey(todayKey);
  return addDaysToDateKey(todayKey, weekday === 0 || weekday === 6 ? 0 : 6 - weekday);
}

export function resolveQuickDate(choice: QuickDateChoice | null, customDate: string, now: Date): string | null {
  const today = todayInRome(now);
  switch (choice) {
    case "today":
      return today;
    case "tomorrow":
      return addDaysToDateKey(today, 1);
    case "weekend":
      return weekendDateKey(today);
    case "custom":
      return isValidDateKey(customDate) ? customDate : null;
    default:
      return null;
  }
}

export function isValidTime(time: string): boolean {
  return isValidTimeOfDay(time);
}

export interface ScheduleCheck {
  /** Istante di inizio in ISO UTC (quello inviato al backend): vuoto se giorno o ora mancano, non sono validi o l'ora non esiste a Milano. */
  readonly startsAt: string;
  readonly issue: ScheduleIssue | null;
  /** Se l'ora capita due volte a Milano: i due istanti possibili. `startsAt` segue `ambiguousChoice`. */
  readonly ambiguous: { readonly first: number; readonly second: number } | null;
}

const EMPTY: Omit<ScheduleCheck, "issue"> = { startsAt: "", ambiguous: null };

export function checkSchedule(
  choice: QuickDateChoice | null,
  customDate: string,
  time: string,
  now: Date,
  ambiguousChoice: AmbiguousChoice = "first",
): ScheduleCheck {
  if (!choice) return { ...EMPTY, issue: "date-missing" };
  const dateKey = resolveQuickDate(choice, customDate, now);
  if (!dateKey) return { ...EMPTY, issue: choice === "custom" && customDate ? "date-invalid" : "date-missing" };
  if (!time) return { ...EMPTY, issue: "time-missing" };
  if (!isValidTimeOfDay(time)) return { ...EMPTY, issue: "time-invalid" };

  const resolution = resolveRomeWallTime(dateKey, time);
  if (resolution.kind === "invalid") return { ...EMPTY, issue: "time-invalid" };
  if (resolution.kind === "nonexistent") return { ...EMPTY, issue: "nonexistent" };

  const ambiguous = resolution.kind === "ambiguous" ? { first: resolution.first, second: resolution.second } : null;
  const instant = resolution.kind === "ambiguous" ? (ambiguousChoice === "second" ? resolution.second : resolution.first) : resolution.instant;
  return { startsAt: new Date(instant).toISOString(), ambiguous, issue: instant <= now.getTime() ? "past" : null };
}

/**
 * Scorciatoie d'orario per il giorno scelto: mai un orario già passato, mai uno che a Milano non esiste.
 * Senza un giorno ancora scelto si propongono tutte (non c'è nulla da escludere); quando nessuna è più possibile (es. stasera tardi) l'elenco è vuoto.
 */
export function suggestTimes(choice: QuickDateChoice | null, customDate: string, now: Date): readonly string[] {
  const dateKey = resolveQuickDate(choice, customDate, now);
  if (!dateKey) return SUGGESTED_TIMES;
  return SUGGESTED_TIMES.filter((time) => {
    const resolution = resolveRomeWallTime(dateKey, time);
    if (resolution.kind === "valid") return resolution.instant > now.getTime();
    if (resolution.kind === "ambiguous") return resolution.second > now.getTime();
    return false;
  });
}

/** Etichetta di un giorno ("giovedì 22 ottobre"): è una data di calendario, non un istante, quindi non dipende da nessun fuso. */
export function formatQuickDate(dateKey: string): string {
  if (!isValidDateKey(dateKey)) return "";
  const [year, month, day] = dateKey.split("-").map(Number);
  return longDateFormatter.format(new Date(Date.UTC(year, month - 1, day, 12)));
}

/** Giorno e ora di un istante, letti a Milano ("gio 22 ott, 21:00"). */
export function formatQuickStart(startsAt: string): string {
  const date = new Date(startsAt);
  return Number.isNaN(date.getTime()) ? "" : shortDateTimeFormatter.format(date);
}

/** "HH:mm" di un istante a Milano: serve a descrivere le due occorrenze di un'ora ambigua. */
export function formatRomeClock(instantMs: number): string {
  return new Intl.DateTimeFormat("it-IT", { timeZone: EVENT_TIME_ZONE, hour: "2-digit", minute: "2-digit", timeZoneName: "short" }).format(new Date(instantMs));
}
