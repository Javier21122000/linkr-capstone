// Bottom sheet della lista eventi: posizioni, regole del gesto e rilascio (logica pura, senza DOM).
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  FLICK_VELOCITY,
  MIN_SNAP_GAP_PX,
  dampedOffset,
  releaseVelocity,
  resolveRelease,
  sheetOffsets,
  sheetOwnsListDrag,
} from "../src/lib/bottomSheet.ts";

// iPhone 13 Pro in Safari: area mappa 664 px, pannello espanso 523 px (dai filtri al bordo basso).
const offsets = sheetOffsets(523, 664);

test("posizioni: espansa a 0, intermedia = pannello meno la quota visibile, chiusa = tutto fuori", () => {
  assert.equal(offsets.full, 0);
  assert.equal(offsets.closed, 523);
  assert.equal(offsets.half, 523 - Math.round(664 * 0.55));
});

test("sugli schermi bassi (orizzontale) lo stato intermedio non esiste: resta espansa o chiusa", () => {
  const landscape = sheetOffsets(300, 390);
  assert.equal(landscape.half, 0);
  assert.equal(resolveRelease({ offset: 200, velocity: 0, from: "full", offsets: landscape }), "closed");
  assert.equal(resolveRelease({ offset: 60, velocity: 0, from: "full", offsets: landscape }), "full");
  assert.ok(MIN_SNAP_GAP_PX > 0);
});

test("la quota intermedia ha un minimo: su un'area piccola mostra comunque header e un evento", () => {
  const small = sheetOffsets(400, 420);
  assert.equal(400 - small.half, 320);
});

test("sopra lo stato espanso la sheet resiste, sotto la chiusura non va oltre", () => {
  assert.ok(dampedOffset(-100, offsets) > -100);
  assert.ok(dampedOffset(-100, offsets) < 0);
  assert.equal(dampedOffset(900, offsets), offsets.closed);
  assert.equal(dampedOffset(120, offsets), 120);
});

test("rilascio da fermo: vince lo stato più vicino", () => {
  assert.equal(resolveRelease({ offset: 20, velocity: 0, from: "half", offsets }), "full");
  assert.equal(resolveRelease({ offset: offsets.half + 10, velocity: 0, from: "half", offsets }), "half");
  assert.equal(resolveRelease({ offset: offsets.half + 190, velocity: 0, from: "half", offsets }), "closed");
});

test("un lancio sposta di uno stato anche se la posizione è vicina: giù da espansa → intermedia, giù da intermedia → chiusa, su → espansa", () => {
  const fast = FLICK_VELOCITY + 0.2;
  assert.equal(resolveRelease({ offset: 30, velocity: fast, from: "full", offsets }), "half");
  assert.equal(resolveRelease({ offset: offsets.half + 20, velocity: fast, from: "half", offsets }), "closed");
  assert.equal(resolveRelease({ offset: offsets.half - 20, velocity: -fast, from: "half", offsets }), "full");
});

test("la lista cede alla sheet solo quando è al primo elemento e il dito va in basso", () => {
  assert.equal(sheetOwnsListDrag({ snap: "full", scrollTop: 0, deltaY: 12 }), true);
  assert.equal(sheetOwnsListDrag({ snap: "full", scrollTop: 140, deltaY: 12 }), false);
  assert.equal(sheetOwnsListDrag({ snap: "half", scrollTop: 0, deltaY: 12 }), true);
});

test("verso l'alto: prima si espande la sheet, poi scorre la lista", () => {
  assert.equal(sheetOwnsListDrag({ snap: "half", scrollTop: 0, deltaY: -12 }), true);
  assert.equal(sheetOwnsListDrag({ snap: "full", scrollTop: 0, deltaY: -12 }), false);
  assert.equal(sheetOwnsListDrag({ snap: "full", scrollTop: 300, deltaY: -12 }), false);
});

test("velocità di rilascio: dagli ultimi campioni, zero se il dito si è fermato", () => {
  const samples = [{ y: 100, t: 0 }, { y: 130, t: 40 }, { y: 170, t: 80 }, { y: 210, t: 120 }];
  assert.ok(Math.abs(releaseVelocity(samples, 125) - 1) < 0.2);
  assert.equal(releaseVelocity(samples, 400), 0);
  assert.equal(releaseVelocity([], 10), 0);
  assert.equal(releaseVelocity([{ y: 5, t: 0 }], 10), 0);
});
