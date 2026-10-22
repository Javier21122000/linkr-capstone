import assert from "node:assert/strict";
import { test } from "node:test";

import { getQuickParticipationAction, getQuickParticipationFeedback } from "../src/lib/quickParticipation.ts";

test("uno stato inattivo viene salvato e il passaggio resta mutuamente esclusivo", () => {
  assert.deepEqual(getQuickParticipationAction(undefined, "INTERESTED"), { kind: "save", status: "INTERESTED" });
  assert.deepEqual(getQuickParticipationAction("INTERESTED", "GOING"), { kind: "save", status: "GOING" });
  assert.deepEqual(getQuickParticipationAction("GOING", "INTERESTED"), { kind: "save", status: "INTERESTED" });
});

test("un click sullo stato già attivo richiede la rimozione", () => {
  assert.deepEqual(getQuickParticipationAction("INTERESTED", "INTERESTED"), { kind: "remove" });
  assert.deepEqual(getQuickParticipationAction("GOING", "GOING"), { kind: "remove" });
});

test("i feedback rapidi rispettano il copy concordato", () => {
  assert.equal(getQuickParticipationFeedback("INTERESTED"), "Aggiunto alla tua agenda · Ti interessa");
  assert.equal(getQuickParticipationFeedback("GOING"), "Perfetto! Ci sarai");
  assert.equal(getQuickParticipationFeedback(null), "Rimosso dalla tua agenda");
});
