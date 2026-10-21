// Classificazione degli errori di MapLibre e regole di tentativo (fatti riprodotti nel browser: vedi lib/mapHealth.ts).
import assert from "node:assert/strict";
import { test } from "node:test";

import { AUTO_RETRY_DELAYS_MS, classifyMapError, describeMapFailure, nextRetryDelay } from "../src/lib/mapHealth.ts";

const error = (url, status) => ({ error: { url, status }, tile: undefined });
const TILE = "https://api.maptiler.com/tiles/v4/11/1076/732?key=SEGRETO";

test("tasselli: rete, 429 e 5xx sono guasti temporanei della cartografia", () => {
  for (const status of [0, 429, 500, 503]) {
    const failure = classifyMapError({ ...error(TILE, status), tile: {} });
    assert.equal(failure.kind, "basemap");
    assert.equal(failure.permanent, false);
    assert.equal(failure.status, status);
  }
});

test("401, 403 e 400 sono permanenti: riprovare non serve", () => {
  for (const status of [400, 401, 403]) assert.equal(classifyMapError({ ...error(TILE, status), tile: {} }).permanent, true);
});

test("un tassello senza dati (404/204) non è un errore", () => {
  assert.equal(classifyMapError({ ...error(TILE, 404), tile: {} }).kind, "ignore");
  assert.equal(classifyMapError({ ...error(TILE, 204), tile: {} }).kind, "ignore");
  // Ma un tilejson mancante sì: senza non esiste nessun tassello.
  assert.equal(classifyMapError(error("https://api.maptiler.com/tiles/v4/tiles.json?key=X", 404)).kind, "basemap");
});

test("stile, sprite, glifi e il resto", () => {
  assert.equal(classifyMapError(error("https://api.maptiler.com/maps/streets-v4/style.json?key=X", 0)).kind, "style");
  assert.equal(classifyMapError(error("https://api.maptiler.com/sprites/general/sprite.png", 0)).kind, "cosmetic");
  assert.equal(classifyMapError(error("https://api.maptiler.com/fonts/Roboto%20Bold/0-255.pbf", 503)).kind, "cosmetic");
  assert.equal(classifyMapError(error("https://example.com/altro", 500)).kind, "ignore");
});

test("senza URL: un tassello è cartografia, il resto è sconosciuto", () => {
  assert.equal(classifyMapError({ error: new Error("x"), tile: {} }).kind, "basemap");
  assert.equal(classifyMapError({ error: new Error("WebGL non disponibile") }).kind, "unknown");
  assert.equal(classifyMapError({}).kind, "unknown");
});

test("tentativi: due soli, con attesa crescente; mai per errori permanenti", () => {
  assert.equal(nextRetryDelay(0, { status: 0, permanent: false }), AUTO_RETRY_DELAYS_MS[0]);
  assert.equal(nextRetryDelay(1, { status: 503, permanent: false }), AUTO_RETRY_DELAYS_MS[1]);
  assert.equal(nextRetryDelay(2, { status: 503, permanent: false }), null);
  assert.equal(nextRetryDelay(0, { status: 403, permanent: true }), null);
  // 429: si aspetta di più, sempre al massimo due tentativi.
  assert.ok(nextRetryDelay(0, { status: 429, permanent: false }) > AUTO_RETRY_DELAYS_MS[1]);
  assert.equal(nextRetryDelay(2, { status: 429, permanent: false }), null);
});

test("messaggi per l'utente: onesti sul tipo di problema, senza il testo dell'errore né la chiave", () => {
  assert.match(describeMapFailure({ status: 403, permanent: true }), /errore 403/);
  assert.match(describeMapFailure({ status: 429, permanent: false }), /sovraccarico/);
  assert.match(describeMapFailure({ status: 503, permanent: false }), /non risponde/);
  assert.match(describeMapFailure({ status: 0, permanent: false }), /connessione/i);
  assert.match(describeMapFailure(null), /non si è caricata/);
  for (const failure of [{ status: 403, permanent: true }, { status: 0, permanent: false }, null]) assert.ok(!/SEGRETO|key=/.test(describeMapFailure(failure)));
});
