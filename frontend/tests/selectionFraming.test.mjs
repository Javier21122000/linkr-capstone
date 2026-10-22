// Geometria del ricentramento: dove deve stare il marker selezionato e quando muovere la camera è inutile.
import assert from "node:assert/strict";
import { test } from "node:test";

import { FRAMING_TOLERANCE_PX, isAlreadyFramed, selectionScreenTarget } from "../src/lib/selectionFraming.ts";

test("il punto va al centro dell'area libera, non al centro della mappa", () => {
  // Desktop: pannello a sinistra (440), controlli a destra (72), filtri in alto (200 col pin), anteprima in basso (300).
  const target = selectionScreenTarget({ width: 1440, height: 900 }, { left: 440, right: 72, top: 200, bottom: 300 });
  assert.deepEqual(target, { x: 440 + (1440 - 440 - 72) / 2, y: 200 + (900 - 200 - 300) / 2 });
  assert.deepEqual(selectionScreenTarget({ width: 390, height: 800 }, { left: 40, right: 40, top: 100, bottom: 100 }), { x: 195, y: 400 });
});

test("margini asimmetrici spostano il bersaglio verso l'area davvero libera", () => {
  const free = selectionScreenTarget({ width: 1000, height: 800 }, { left: 0, right: 0, top: 0, bottom: 0 });
  const withPanel = selectionScreenTarget({ width: 1000, height: 800 }, { left: 400, right: 0, top: 0, bottom: 200 });
  assert.ok(withPanel.x > free.x);
  assert.ok(withPanel.y < free.y);
});

test("già al centro (entro la tolleranza) non c'è nulla da animare; appena fuori sì", () => {
  const target = { x: 700, y: 400 };
  assert.equal(isAlreadyFramed({ x: 700, y: 400 }, target), true);
  assert.equal(isAlreadyFramed({ x: 700 + FRAMING_TOLERANCE_PX, y: 400 - FRAMING_TOLERANCE_PX }, target), true);
  assert.equal(isAlreadyFramed({ x: 700 + FRAMING_TOLERANCE_PX + 0.5, y: 400 }, target), false);
  assert.equal(isAlreadyFramed({ x: 700, y: 400 + 80 }, target), false);
});
