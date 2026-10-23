// Dimensione della mappa rispetto al contenitore: quando serve riallinearla dopo il ritorno di Discover.
import assert from "node:assert/strict";
import { test } from "node:test";

import { MAP_SIZE_CHECK_DELAY_MS, canvasNeedsResize } from "../src/lib/mapSize.ts";

const size = (width, height) => ({ width, height });

test("il caso del difetto: canvas di ripiego 400×300 dentro un contenitore visibile più grande → serve resize", () => {
  assert.equal(canvasNeedsResize(size(400, 300), size(710, 527)), true);
  assert.equal(canvasNeedsResize(size(400, 300), size(1440, 900)), true);
});

test("canvas e contenitore uguali → niente da fare", () => {
  assert.equal(canvasNeedsResize(size(710, 527), size(710, 527)), false);
});

test("basta una dimensione sbagliata, in qualunque direzione (canvas più piccolo o più grande)", () => {
  assert.equal(canvasNeedsResize(size(710, 300), size(710, 527)), true);
  assert.equal(canvasNeedsResize(size(400, 527), size(710, 527)), true);
  assert.equal(canvasNeedsResize(size(1440, 900), size(710, 527)), true);
});

test("uno scarto di 1 px (larghezza CSS frazionaria del canvas) non conta", () => {
  assert.equal(canvasNeedsResize(size(711, 527), size(710, 527)), false);
  assert.equal(canvasNeedsResize(size(709, 526), size(710, 527)), false);
  assert.equal(canvasNeedsResize(size(712, 527), size(710, 527)), true);
});

test("con il contenitore nascosto (0×0) non si ridimensiona mai: MapLibre ripiegherebbe di nuovo su 400×300", () => {
  assert.equal(canvasNeedsResize(size(400, 300), size(0, 0)), false);
  assert.equal(canvasNeedsResize(size(710, 527), size(0, 0)), false);
  assert.equal(canvasNeedsResize(size(400, 300), size(710, 0)), false);
  assert.equal(canvasNeedsResize(size(400, 300), size(0, 527)), false);
});

test("il controllo lascia al ResizeObserver il tempo di agire per primo, senza far aspettare chi guarda", () => {
  assert.ok(MAP_SIZE_CHECK_DELAY_MS >= 100 && MAP_SIZE_CHECK_DELAY_MS <= 500, String(MAP_SIZE_CHECK_DELAY_MS));
});
