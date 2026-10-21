/**
 * Ora di Milano (Europe/Rome), indipendente dal fuso del dispositivo.
 *
 * Un evento è alle 20:30 *di Milano*: chi lo crea da New York o con l'orologio del telefono sbagliato sceglie comunque l'ora di Milano
 * e il backend riceve sempre l'istante UTC corretto. Tutto passa da `Intl` con `timeZone: "Europe/Rome"`: nessuna tabella dell'ora legale.
 *
 * Modulo senza dipendenze (testabile con `node --test`): le importazioni tra moduli puri usano l'estensione `.ts`.
 */
export const EVENT_TIME_ZONE = "Europe/Rome";

const MINUTE_MS = 60_000;
const DAY_MS = 24 * 60 * MINUTE_MS;

const wallClockFormatter = new Intl.DateTimeFormat("en-GB", {
  timeZone: EVENT_TIME_ZONE,
  hourCycle: "h23",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

/** Data e ora che un orologio a Milano segnerebbe in un dato istante. */
export interface WallClock {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

export function wallClockAt(instantMs: number): WallClock {
  const values: Record<string, number> = {};
  for (const part of wallClockFormatter.formatToParts(new Date(instantMs))) {
    if (part.type !== "literal") values[part.type] = Number(part.value);
  }
  return { year: values.year, month: values.month, day: values.day, hour: values.hour, minute: values.minute, second: values.second };
}

/** Scarto di Milano da UTC in minuti in quell'istante: 60 (ora solare) o 120 (ora legale). */
export function offsetMinutesAt(instantMs: number): number {
  const wall = wallClockAt(instantMs);
  const wallAsUtc = Date.UTC(wall.year, wall.month - 1, wall.day, wall.hour, wall.minute, wall.second);
  return Math.round((wallAsUtc - Math.floor(instantMs / 1000) * 1000) / MINUTE_MS);
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

// ——— Giorni di calendario (chiavi AAAA-MM-GG) ———
// Si calcolano su `Date.UTC`: un giorno di calendario non è mai "24 ore", quindi il cambio d'ora non li sposta.

const DATE_KEY = /^(\d{4})-(\d{2})-(\d{2})$/;

export function isValidDateKey(key: string): boolean {
  const match = DATE_KEY.exec(key);
  if (!match) return false;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return date.getUTCFullYear() === Number(match[1]) && date.getUTCMonth() === Number(match[2]) - 1 && date.getUTCDate() === Number(match[3]);
}

/** Giorno di calendario a Milano in quell'istante. */
export function romeDateKey(instantMs: number): string {
  const wall = wallClockAt(instantMs);
  return `${wall.year}-${pad(wall.month)}-${pad(wall.day)}`;
}

export function addDaysToDateKey(key: string, days: number): string {
  const match = DATE_KEY.exec(key);
  if (!match) return key;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + days));
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
}

/** 0 = domenica … 6 = sabato. */
export function weekdayOfDateKey(key: string): number {
  const match = DATE_KEY.exec(key);
  if (!match) return 0;
  return new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))).getUTCDay();
}

/** "HH:mm" a Milano in quell'istante. */
export function romeTimeOfDay(instantMs: number): string {
  const wall = wallClockAt(instantMs);
  return `${pad(wall.hour)}:${pad(wall.minute)}`;
}

// ——— Da ora di Milano a istante ———

const TIME_OF_DAY = /^([01]\d|2[0-3]):([0-5]\d)$/;

export function isValidTimeOfDay(time: string): boolean {
  return TIME_OF_DAY.test(time);
}

/**
 * Esito della lettura di "giorno + ora di Milano":
 * - `valid`: un solo istante;
 * - `ambiguous`: l'ora capita due volte (l'ultima domenica di ottobre, tra le 02:00 e le 02:59): `first` è la prima (ora legale),
 *   `second` la seconda (ora solare);
 * - `nonexistent`: l'ora non esiste (l'ultima domenica di marzo, tra le 02:00 e le 02:59, si salta da 02:00 a 03:00);
 * - `invalid`: giorno o ora non sono scritti in modo valido.
 */
export type WallTimeResolution =
  | { readonly kind: "valid"; readonly instant: number }
  | { readonly kind: "ambiguous"; readonly first: number; readonly second: number }
  | { readonly kind: "nonexistent" }
  | { readonly kind: "invalid" };

export function resolveRomeWallTime(dateKey: string, time: string): WallTimeResolution {
  const dateMatch = DATE_KEY.exec(dateKey);
  const timeMatch = TIME_OF_DAY.exec(time);
  if (!dateMatch || !timeMatch || !isValidDateKey(dateKey)) return { kind: "invalid" };

  const wallAsUtc = Date.UTC(Number(dateMatch[1]), Number(dateMatch[2]) - 1, Number(dateMatch[3]), Number(timeMatch[1]), Number(timeMatch[2]));
  // L'istante vero dista al massimo due ore da `wallAsUtc`: gli scarti possibili sono quelli di un giorno prima e di un giorno dopo.
  const offsets = new Set([offsetMinutesAt(wallAsUtc - DAY_MS), offsetMinutesAt(wallAsUtc + DAY_MS)]);
  const instants: number[] = [];
  for (const offset of offsets) {
    const candidate = wallAsUtc - offset * MINUTE_MS;
    // Il candidato vale solo se a quell'istante Milano ha davvero quello scarto (altrimenti l'ora cade nel salto).
    if (offsetMinutesAt(candidate) === offset) instants.push(candidate);
  }
  instants.sort((a, b) => a - b);

  if (instants.length === 0) return { kind: "nonexistent" };
  if (instants.length === 1) return { kind: "valid", instant: instants[0] };
  return { kind: "ambiguous", first: instants[0], second: instants[1] };
}

// ——— Valori dei form: "AAAA-MM-GGTHH:mm" è ora di Milano, un ISO con `Z` o scarto è già un istante ———

const LOCAL_DATE_TIME = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})$/;

/**
 * Istante di un valore di form. `AAAA-MM-GGTHH:mm` (formato di `datetime-local`) è ora di Milano, non del dispositivo.
 * `NaN` se il valore non è una data valida o l'ora non esiste a Milano; se l'ora capita due volte vale la prima.
 */
export function parseEventInstant(value: string): number {
  const local = LOCAL_DATE_TIME.exec(value);
  if (!local) return Date.parse(value);
  const resolution = resolveRomeWallTime(local[1], local[2]);
  if (resolution.kind === "valid") return resolution.instant;
  if (resolution.kind === "ambiguous") return resolution.first;
  return Number.NaN;
}

/** Valore `AAAA-MM-GGTHH:mm` (ora di Milano) per un `datetime-local` che mostra un istante ricevuto dal backend. */
export function toRomeLocalInput(isoInstant: string | null): string {
  if (!isoInstant) return "";
  const instant = Date.parse(isoInstant);
  if (!Number.isFinite(instant)) return "";
  return `${romeDateKey(instant)}T${romeTimeOfDay(instant)}`;
}

/** `true` se l'orologio del dispositivo, in quell'istante, non segna la stessa ora di Milano. */
export function deviceZoneDiffersFromRome(instantMs: number): boolean {
  return -new Date(instantMs).getTimezoneOffset() !== offsetMinutesAt(instantMs);
}
