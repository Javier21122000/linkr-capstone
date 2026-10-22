import assert from "node:assert/strict";
import { test } from "node:test";

import { countAdminEvents, filterAdminEvents } from "../src/lib/adminPresentation.ts";

const event = (overrides) => ({
  id: "1",
  title: "Concerto al Parco",
  category: "MUSIC",
  startsAt: "2026-10-23T18:00:00+02:00",
  endsAt: null,
  venueName: "Parco della Musica",
  neighbourhood: "Flaminio",
  latitude: 41.9,
  longitude: 12.4,
  imageUrl: null,
  imageAlt: null,
  demo: false,
  status: "PUBLISHED",
  type: "OFFICIAL",
  source: null,
  ...overrides,
});

const events = [
  event({ id: "tm", source: { provider: "TICKETMASTER", url: null } }),
  event({ id: "community", title: "Cena sociale", category: "FOOD", venueName: "Trastevere", type: "COMMUNITY" }),
  event({ id: "demo", title: "Evento demo", demo: true }),
];

test("calcola le statistiche admin esclusivamente dai campi reali degli eventi", () => {
  assert.deepEqual(countAdminEvents(events), { total: 3, ticketmaster: 1, community: 1, demo: 1 });
});

test("filtra eventi per testo, fonte e categoria combinati", () => {
  assert.deepEqual(filterAdminEvents(events, "trastevere", "LINKR", "FOOD").map(({ id }) => id), ["community"]);
  assert.deepEqual(filterAdminEvents(events, "", "TICKETMASTER", "ALL").map(({ id }) => id), ["tm"]);
  assert.deepEqual(filterAdminEvents(events, "demo", "ALL", "MUSIC").map(({ id }) => id), ["demo"]);
});
