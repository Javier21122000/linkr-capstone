// Test di Quick Create: scorciatoie di data, suggerimenti d'orario contestuali, orari inesistenti e ambigui.
import assert from "node:assert/strict";
import { test } from "node:test";

import { checkSchedule, formatQuickDate, formatQuickStart, resolveQuickDate, suggestTimes, todayInRome } from "../src/lib/quickCreateSchedule.ts";

const at = (value) => new Date(value);

test("Oggi / Domani / Questo weekend seguono il calendario di Milano", () => {
  const wednesday = at("2026-10-21T10:00:00Z"); // mercoledì 21 ottobre
  assert.equal(resolveQuickDate("today", "", wednesday), "2026-10-21");
  assert.equal(resolveQuickDate("tomorrow", "", wednesday), "2026-10-22");
  assert.equal(resolveQuickDate("weekend", "", wednesday), "2026-10-24"); // prossimo sabato
  assert.equal(resolveQuickDate("weekend", "", at("2026-10-24T10:00:00Z")), "2026-10-24"); // sabato: oggi
  assert.equal(resolveQuickDate("weekend", "", at("2026-10-25T10:00:00Z")), "2026-10-25"); // domenica: oggi
  assert.equal(resolveQuickDate("custom", "2026-11-05", wednesday), "2026-11-05");
  assert.equal(resolveQuickDate("custom", "2026-02-31", wednesday), null);
  assert.equal(resolveQuickDate(null, "", wednesday), null);
});

test("vicino alla mezzanotte il giorno è quello di Milano, non quello del dispositivo", () => {
  const previous = process.env.TZ;
  process.env.TZ = "America/New_York";
  try {
    const now = at("2026-10-24T22:30:00Z"); // 00:30 di domenica 25 a Milano, 18:30 di sabato 24 a New York
    assert.equal(todayInRome(now), "2026-10-25");
    assert.equal(resolveQuickDate("today", "", now), "2026-10-25");
    assert.equal(resolveQuickDate("tomorrow", "", now), "2026-10-26");
  } finally {
    process.env.TZ = previous;
  }
});

test("l'istante inviato è l'ora di Milano, con il dispositivo in un altro fuso", () => {
  const previous = process.env.TZ;
  process.env.TZ = "America/New_York";
  try {
    const now = at("2026-10-21T10:00:00Z");
    const check = checkSchedule("tomorrow", "", "20:30", now);
    assert.equal(check.issue, null);
    assert.equal(check.startsAt, "2026-10-22T18:30:00.000Z"); // 20:30 a Milano (CEST), non 20:30 a New York
  } finally {
    process.env.TZ = previous;
  }
});

test("orario passato per oggi: errore; per domani: valido", () => {
  const now = at("2026-10-21T17:00:00Z"); // 19:00 a Milano
  assert.equal(checkSchedule("today", "", "18:30", now).issue, "past");
  assert.equal(checkSchedule("today", "", "19:00", now).issue, "past"); // alla stessa ora non è futuro
  assert.equal(checkSchedule("today", "", "19:01", now).issue, null);
  assert.equal(checkSchedule("tomorrow", "", "07:00", now).issue, null);
});

test("campi mancanti o non validi", () => {
  const now = at("2026-10-21T10:00:00Z");
  assert.equal(checkSchedule(null, "", "20:00", now).issue, "date-missing");
  assert.equal(checkSchedule("custom", "", "20:00", now).issue, "date-missing");
  assert.equal(checkSchedule("custom", "2026-02-31", "20:00", now).issue, "date-invalid");
  assert.equal(checkSchedule("today", "", "", now).issue, "time-missing");
  assert.equal(checkSchedule("today", "", "25:00", now).issue, "time-invalid");
  assert.equal(checkSchedule("today", "", "9:00", now).issue, "time-invalid");
});

test("orario inesistente (cambio all'ora legale): nessun istante, errore dedicato", () => {
  const now = at("2027-03-01T10:00:00Z");
  const check = checkSchedule("custom", "2027-03-28", "02:30", now);
  assert.equal(check.issue, "nonexistent");
  assert.equal(check.startsAt, "");
});

test("orario ambiguo (cambio all'ora solare): due istanti, la scelta decide", () => {
  const now = at("2026-10-21T10:00:00Z");
  const first = checkSchedule("weekend", "", "02:30", at("2026-10-25T10:00:00Z"), "first");
  // Il 25 ottobre alle 10:00Z "oggi 02:30" è già passato: si prova il giorno successivo per la parte ambigua.
  assert.equal(first.issue, "past");
  const saturdayNow = at("2026-10-24T10:00:00Z");
  const custom = checkSchedule("custom", "2026-10-25", "02:30", saturdayNow, "first");
  assert.equal(custom.issue, null);
  assert.equal(custom.startsAt, "2026-10-25T00:30:00.000Z");
  assert.ok(custom.ambiguous);
  assert.equal(checkSchedule("custom", "2026-10-25", "02:30", saturdayNow, "second").startsAt, "2026-10-25T01:30:00.000Z");
  assert.equal(checkSchedule("custom", "2026-10-25", "03:00", now).ambiguous, null);
});

test("suggerimenti d'orario: contestuali, mai passati", () => {
  assert.deepEqual(suggestTimes("tomorrow", "", at("2026-10-21T10:00:00Z")), ["15:00", "19:00", "21:00"]);
  assert.deepEqual(suggestTimes(null, "", at("2026-10-21T10:00:00Z")), ["15:00", "19:00", "21:00"]);
  // 16:00 a Milano (14:00Z): resta 19:00 e 21:00.
  assert.deepEqual(suggestTimes("today", "", at("2026-10-21T14:00:00Z")), ["19:00", "21:00"]);
  // 19:00 a Milano: 19:00 è "adesso", non più futuro.
  assert.deepEqual(suggestTimes("today", "", at("2026-10-21T17:00:00Z")), ["21:00"]);
  // 22:30 a Milano: stasera non c'è più nulla da suggerire.
  assert.deepEqual(suggestTimes("today", "", at("2026-10-21T20:30:00Z")), []);
  // Un giorno scelto tra una settimana: tutti.
  assert.deepEqual(suggestTimes("custom", "2026-11-05", at("2026-10-21T20:30:00Z")), ["15:00", "19:00", "21:00"]);
});

test("etichette: giorno di calendario e inizio letti a Milano", () => {
  assert.match(formatQuickDate("2026-10-22"), /giovedì 22 ottobre/i);
  assert.equal(formatQuickDate("2026-02-31"), "");
  const previous = process.env.TZ;
  process.env.TZ = "Asia/Tokyo";
  try {
    assert.match(formatQuickStart("2026-10-22T19:00:00.000Z"), /21:00/); // 21:00 a Milano, anche con il dispositivo a Tokyo
  } finally {
    process.env.TZ = previous;
  }
});
