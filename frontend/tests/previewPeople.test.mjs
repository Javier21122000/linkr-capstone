// Riga social dell'anteprima evento: conteggi veri, avatar al massimo 4 (prima chi viene), nessun numero inventato.
import assert from "node:assert/strict";
import { test } from "node:test";

import { PREVIEW_AVATARS, previewPeopleLabel, summarizePreviewPeople } from "../src/lib/previewPeople.ts";

const person = (n, status) => ({ userId: `u${n}`, username: `u${n}`, displayName: `U${n}`, avatarUrl: null, status });
const page = (items, totalElements = items.length) => ({ items, page: 0, size: 50, totalElements, totalPages: totalElements > items.length ? 2 : 1 });

test("conta chi viene e chi è interessato, con gli avatar di chi viene per primi", () => {
  const summary = summarizePreviewPeople(page([person(1, "INTERESTED"), person(2, "GOING"), person(3, "INTERESTED"), person(4, "GOING"), person(5, "INTERESTED"), person(6, "INTERESTED")]));
  assert.deepEqual([summary.total, summary.going, summary.interested, summary.exact], [6, 2, 4, true]);
  assert.equal(summary.avatars.length, PREVIEW_AVATARS);
  assert.deepEqual(summary.avatars.slice(0, 2).map((p) => p.userId), ["u2", "u4"]);
  assert.equal(previewPeopleLabel(summary), "2 vengono · 4 interessati");
});

test("singolari, un solo gruppo e nessuno", () => {
  assert.equal(previewPeopleLabel(summarizePreviewPeople(page([person(1, "GOING"), person(2, "INTERESTED")]))), "1 viene · 1 interessato");
  assert.equal(previewPeopleLabel(summarizePreviewPeople(page([person(1, "INTERESTED")]))), "1 interessato");
  const none = summarizePreviewPeople(page([]));
  assert.equal(previewPeopleLabel(none), "");
  assert.equal(none.avatars.length, 0);
});

test("oltre la pagina letta i conteggi sono un minimo dichiarato, mai un totale inventato", () => {
  const items = Array.from({ length: 50 }, (_, i) => person(i, i % 2 ? "GOING" : "INTERESTED"));
  const summary = summarizePreviewPeople(page(items, 130));
  assert.equal(summary.exact, false);
  assert.equal(summary.total, 130);
  assert.equal(previewPeopleLabel(summary), "almeno 25 vengono · almeno 25 interessati");
});
