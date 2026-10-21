// Test del fuso orario di Milano: ora legale/solare, orari inesistenti e ambigui, indipendenza dal fuso del dispositivo.
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  addDaysToDateKey,
  deviceZoneDiffersFromRome,
  isValidDateKey,
  offsetMinutesAt,
  parseEventInstant,
  resolveRomeWallTime,
  romeDateKey,
  romeTimeOfDay,
  toRomeLocalInput,
  weekdayOfDateKey,
} from "../src/lib/romeTime.ts";

const iso = (ms) => new Date(ms).toISOString();

test("scarto di Milano: +01:00 in inverno, +02:00 in estate", () => {
  assert.equal(offsetMinutesAt(Date.parse("2026-01-15T12:00:00Z")), 60);
  assert.equal(offsetMinutesAt(Date.parse("2026-07-15T12:00:00Z")), 120);
});

test("20:30 di Milano è sempre 20:30 di Milano: 18:30Z d'estate, 19:30Z d'inverno", () => {
  const summer = resolveRomeWallTime("2026-07-10", "20:30");
  assert.equal(summer.kind, "valid");
  assert.equal(iso(summer.instant), "2026-07-10T18:30:00.000Z");
  const winter = resolveRomeWallTime("2026-12-12", "20:30");
  assert.equal(winter.kind, "valid");
  assert.equal(iso(winter.instant), "2026-12-12T19:30:00.000Z");
});

for (const zone of ["America/New_York", "Asia/Tokyo", "Pacific/Auckland", "UTC", "Europe/Rome"]) {
  test(`indipendente dal fuso del dispositivo (${zone})`, () => {
    const previous = process.env.TZ;
    process.env.TZ = zone;
    try {
      assert.equal(iso(resolveRomeWallTime("2026-10-24", "20:30").instant), "2026-10-24T18:30:00.000Z");
      assert.equal(iso(parseEventInstant("2026-12-12T20:30")), "2026-12-12T19:30:00.000Z");
      assert.equal(romeDateKey(Date.parse("2026-10-24T22:30:00Z")), "2026-10-25"); // mezzanotte passata a Milano, non a New York
      assert.equal(romeTimeOfDay(Date.parse("2026-10-24T18:30:00Z")), "20:30");
      assert.equal(toRomeLocalInput("2026-12-12T19:30:00.000Z"), "2026-12-12T20:30");
    } finally {
      process.env.TZ = previous;
    }
  });
}

test("cambio all'ora solare (25 ottobre 2026): 02:00–02:59 capita due volte", () => {
  const resolution = resolveRomeWallTime("2026-10-25", "02:30");
  assert.equal(resolution.kind, "ambiguous");
  assert.equal(iso(resolution.first), "2026-10-25T00:30:00.000Z"); // 02:30 CEST (+02:00)
  assert.equal(iso(resolution.second), "2026-10-25T01:30:00.000Z"); // 02:30 CET (+01:00)
  // Prima e dopo l'ora doppia l'orario è normale.
  assert.equal(iso(resolveRomeWallTime("2026-10-25", "01:59").instant), "2026-10-24T23:59:00.000Z");
  assert.equal(iso(resolveRomeWallTime("2026-10-25", "03:00").instant), "2026-10-25T02:00:00.000Z");
  assert.equal(resolveRomeWallTime("2026-10-25", "02:00").kind, "ambiguous");
  assert.equal(resolveRomeWallTime("2026-10-25", "02:59").kind, "ambiguous");
});

test("cambio all'ora legale (28 marzo 2027): 02:00–02:59 non esiste", () => {
  assert.equal(resolveRomeWallTime("2027-03-28", "02:00").kind, "nonexistent");
  assert.equal(resolveRomeWallTime("2027-03-28", "02:30").kind, "nonexistent");
  assert.equal(resolveRomeWallTime("2027-03-28", "02:59").kind, "nonexistent");
  assert.equal(iso(resolveRomeWallTime("2027-03-28", "01:59").instant), "2027-03-28T00:59:00.000Z");
  assert.equal(iso(resolveRomeWallTime("2027-03-28", "03:00").instant), "2027-03-28T01:00:00.000Z");
  // 29 marzo 2026 (anno precedente) segue la stessa regola.
  assert.equal(resolveRomeWallTime("2026-03-29", "02:15").kind, "nonexistent");
});

test("valori di form: ora di Milano; l'ora inesistente non è un istante; l'ambigua vale la prima", () => {
  assert.ok(Number.isNaN(parseEventInstant("2027-03-28T02:30")));
  assert.equal(iso(parseEventInstant("2026-10-25T02:30")), "2026-10-25T00:30:00.000Z");
  assert.equal(iso(parseEventInstant("2026-10-24T18:30:00.000Z")), "2026-10-24T18:30:00.000Z"); // un ISO è già un istante
  assert.ok(Number.isNaN(parseEventInstant("")));
  assert.ok(Number.isNaN(parseEventInstant("2026-13-40T10:00")));
});

test("andata e ritorno: istante → campo del form → istante", () => {
  for (const value of ["2026-01-10T09:00:00.000Z", "2026-07-10T09:00:00.000Z", "2026-10-25T00:30:00.000Z", "2026-10-25T01:30:00.000Z"]) {
    const local = toRomeLocalInput(value);
    const back = parseEventInstant(local);
    // L'ora doppia perde l'informazione "quale delle due": il ritorno dà sempre la prima, cioè quella legale.
    const expected = value === "2026-10-25T01:30:00.000Z" ? "2026-10-25T00:30:00.000Z" : value;
    assert.equal(iso(back), expected, `${value} → ${local}`);
  }
});

test("giorni di calendario non dipendono dalle 24 ore", () => {
  assert.equal(addDaysToDateKey("2026-10-24", 1), "2026-10-25");
  assert.equal(addDaysToDateKey("2026-10-25", 1), "2026-10-26"); // giorno di 25 ore
  assert.equal(addDaysToDateKey("2027-03-28", 1), "2027-03-29"); // giorno di 23 ore
  assert.equal(addDaysToDateKey("2026-12-31", 1), "2027-01-01");
  assert.equal(weekdayOfDateKey("2026-10-24"), 6); // sabato
  assert.equal(weekdayOfDateKey("2026-10-25"), 0); // domenica
  assert.equal(isValidDateKey("2026-02-31"), false);
  assert.equal(isValidDateKey("2028-02-29"), true);
});

test("deviceZoneDiffersFromRome riflette il fuso del processo", () => {
  const previous = process.env.TZ;
  try {
    process.env.TZ = "Europe/Rome";
    assert.equal(deviceZoneDiffersFromRome(Date.parse("2026-07-10T12:00:00Z")), false);
    process.env.TZ = "America/New_York";
    assert.equal(deviceZoneDiffersFromRome(Date.parse("2026-07-10T12:00:00Z")), true);
  } finally {
    process.env.TZ = previous;
  }
});

test("andata e ritorno esaustivo: ogni mezz'ora dal 2026 al 2028", async () => {
  const { wallClockAt } = await import("../src/lib/romeTime.ts");
  const pad = (n) => String(n).padStart(2, "0");
  let valid = 0;
  let ambiguous = 0;
  let nonexistent = 0;
  for (let day = Date.UTC(2026, 0, 1); day < Date.UTC(2029, 0, 1); day += 86_400_000) {
    const date = new Date(day);
    const key = `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
    for (let half = 0; half < 48; half += 1) {
      const time = `${pad(Math.floor(half / 2))}:${half % 2 ? "30" : "00"}`;
      const resolution = resolveRomeWallTime(key, time);
      const instants = resolution.kind === "valid" ? [resolution.instant] : resolution.kind === "ambiguous" ? [resolution.first, resolution.second] : [];
      if (resolution.kind === "valid") valid += 1;
      if (resolution.kind === "ambiguous") ambiguous += 1;
      if (resolution.kind === "nonexistent") nonexistent += 1;
      for (const instant of instants) {
        const wall = wallClockAt(instant);
        assert.equal(`${wall.year}-${pad(wall.month)}-${pad(wall.day)} ${pad(wall.hour)}:${pad(wall.minute)}`, `${key} ${time}`);
      }
      if (resolution.kind === "ambiguous") assert.equal(resolution.second - resolution.first, 3_600_000);
    }
  }
  // Tre anni: tre cambi all'ora legale (due slot da mezz'ora inesistenti ciascuno) e tre all'ora solare (due slot doppi ciascuno).
  assert.equal(nonexistent, 6);
  assert.equal(ambiguous, 6);
  assert.equal(valid + nonexistent + ambiguous, 1096 * 48);
});
