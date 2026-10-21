// Il controllore di salute della mappa con una mappa finta e timer simulati: nessun ciclo infinito, nessun reset inutile.
import assert from "node:assert/strict";
import { afterEach, beforeEach, mock, test } from "node:test";

import { attachMapRecovery } from "../src/lib/mapRecovery.ts";

const TILE_URL = "https://api.maptiler.com/tiles/v4/11/1076/732";

class FakeMap {
  constructor() { this.handlers = new Map(); this.features = 400; }
  on(type, handler) { this.handlers.set(type, [...(this.handlers.get(type) ?? []), handler]); }
  off(type, handler) { this.handlers.set(type, (this.handlers.get(type) ?? []).filter((item) => item !== handler)); }
  emit(type, event = {}) { for (const handler of this.handlers.get(type) ?? []) handler(event); }
  queryRenderedFeatures() { return new Array(this.features).fill(0); }
  listenerCount() { return [...this.handlers.values()].reduce((total, list) => total + list.length, 0); }
}

function setup(overrides = {}) {
  const map = new FakeMap();
  const log = { states: [], reloads: 0, recreates: 0 };
  const recovery = attachMapRecovery(map, {
    reload: () => { log.reloads += 1; },
    recreate: () => { log.recreates += 1; },
    onChange: (state) => log.states.push(state.phase),
    isVisible: () => true,
    ...overrides,
  });
  return { map, log, recovery };
}

const tileFailure = (status) => ({ error: { url: TILE_URL, status }, tile: {}, sourceId: "maptiler_planet_v4" });

beforeEach(() => mock.timers.enable({ apis: ["setTimeout"] }));
afterEach(() => mock.timers.reset());

test("caricamento pulito: pronta, nessun tentativo", () => {
  const { map, log } = setup();
  map.emit("load"); map.emit("idle");
  assert.deepEqual(log.states, ["ready"]);
  assert.equal(log.reloads, 0);
});

test("tasselli falliti ma load/idle comunque (il bug riprodotto): non è «pronta», si recupera da sola", () => {
  const { map, log } = setup();
  for (let i = 0; i < 4; i += 1) map.emit("error", tileFailure(0));
  map.features = 0;
  map.emit("load"); map.emit("idle");
  assert.deepEqual(log.states, ["recovering"]);
  assert.equal(log.reloads, 0);
  mock.timers.tick(1_500);
  assert.equal(log.reloads, 1);
  // Il ricaricamento riesce: arrivano le feature e l'idle successivo dichiara la mappa pronta.
  map.features = 424; map.emit("idle");
  assert.deepEqual(log.states, ["recovering", "ready"]);
});

test("errori persistenti: due tentativi e poi «incompleta», mai un ciclo infinito", () => {
  const { map, log } = setup();
  map.features = 0;
  map.emit("error", tileFailure(503)); map.emit("load"); map.emit("idle");
  mock.timers.tick(1_500);
  map.emit("error", tileFailure(503)); map.emit("idle");
  mock.timers.tick(4_500);
  map.emit("error", tileFailure(503)); map.emit("idle");
  assert.equal(log.reloads, 2);
  assert.equal(log.states.at(-1), "degraded");
  mock.timers.tick(60_000);
  map.emit("idle"); map.emit("idle");
  assert.equal(log.reloads, 2, "nessun tentativo oltre il secondo");
});

test("errore permanente (403): subito «incompleta», nessun tentativo automatico", () => {
  const { map, log } = setup();
  map.features = 0;
  map.emit("error", tileFailure(403)); map.emit("load"); map.emit("idle");
  assert.deepEqual(log.states, ["degraded"]);
  mock.timers.tick(60_000);
  assert.equal(log.reloads, 0);
});

test("429: attesa più lunga del normale", () => {
  const { map, log } = setup();
  map.features = 0;
  map.emit("error", tileFailure(429)); map.emit("load"); map.emit("idle");
  mock.timers.tick(4_500);
  assert.equal(log.reloads, 0);
  mock.timers.tick(1_000);
  assert.equal(log.reloads, 1);
});

test("un tassello 404 non scatena nulla", () => {
  const { map, log } = setup();
  map.emit("error", tileFailure(404)); map.emit("load"); map.emit("idle");
  assert.deepEqual(log.states, ["ready"]);
});

test("mappa vuota senza alcun errore: due tentativi di recupero, poi incompleta", () => {
  const { map, log } = setup();
  map.features = 0;
  map.emit("load"); map.emit("idle");
  mock.timers.tick(1_500); map.emit("idle");
  mock.timers.tick(4_500); map.emit("idle");
  assert.equal(log.reloads, 2);
  assert.equal(log.states.at(-1), "degraded");
});

test("con la pagina nascosta «zero feature» non è una mappa vuota", () => {
  const { map, log } = setup({ isVisible: () => false });
  map.features = 0;
  map.emit("load"); map.emit("idle");
  assert.deepEqual(log.states, ["ready"]);
});

test("gli idle durante l'attesa non accumulano tentativi", () => {
  const { map, log } = setup();
  map.features = 0;
  map.emit("error", tileFailure(0)); map.emit("load"); map.emit("idle");
  for (let i = 0; i < 20; i += 1) map.emit("idle");
  mock.timers.tick(1_500);
  assert.equal(log.reloads, 1);
});

test("stile non scaricabile prima del load: errore, e «Riprova» ricrea la mappa", () => {
  const { map, log, recovery } = setup();
  map.emit("error", { error: { url: "https://api.maptiler.com/maps/streets-v4/style.json?key=X", status: 0 } });
  assert.deepEqual(log.states, ["error"]);
  recovery.retry();
  assert.equal(log.recreates, 1);
  assert.equal(log.reloads, 0);
});

test("un errore senza URL dopo il load non rovina una mappa che funziona", () => {
  const { map, log } = setup();
  map.emit("load"); map.emit("idle");
  map.emit("error", { error: new Error("qualcosa") });
  map.emit("idle");
  assert.deepEqual(log.states, ["ready"]);
});

test("«Riprova» manuale su una mappa incompleta: azzera i tentativi e ricarica", () => {
  const { map, log, recovery } = setup();
  map.features = 0;
  map.emit("error", tileFailure(403)); map.emit("load"); map.emit("idle");
  assert.equal(log.states.at(-1), "degraded");
  recovery.retry();
  assert.equal(log.reloads, 1);
  assert.equal(log.states.at(-1), "recovering");
  map.features = 424; map.emit("idle");
  assert.equal(log.states.at(-1), "ready");
});

test("contesto WebGL perso: in ripristino; se non torna, incompleta e «Riprova» ricrea", () => {
  const { map, log, recovery } = setup();
  map.emit("load"); map.emit("idle");
  map.emit("webglcontextlost");
  assert.equal(log.states.at(-1), "recovering");
  mock.timers.tick(5_000);
  assert.equal(log.states.at(-1), "degraded");
  recovery.retry();
  assert.equal(log.recreates, 1);
});

test("contesto WebGL ripristinato in tempo: torna pronta all'idle successivo", () => {
  const { map, log } = setup();
  map.emit("load"); map.emit("idle");
  map.emit("webglcontextlost");
  mock.timers.tick(2_000);
  map.emit("webglcontextrestored");
  mock.timers.tick(10_000);
  map.emit("idle");
  assert.equal(log.states.at(-1), "ready");
});

test("rete lenta: dopo il tempo massimo è «lenta» (si continua ad aspettare), poi pronta quando arriva", () => {
  const { map, log } = setup();
  mock.timers.tick(20_000);
  assert.deepEqual(log.states, ["slow"]);
  map.emit("load"); map.emit("idle");
  assert.deepEqual(log.states, ["slow", "ready"]);
});

test("dispose: nessun listener né timer resta in vita", () => {
  const { map, log, recovery } = setup();
  map.features = 0;
  map.emit("error", tileFailure(0)); map.emit("load"); map.emit("idle");
  recovery.dispose();
  assert.equal(map.listenerCount(), 0);
  mock.timers.tick(60_000);
  assert.equal(log.reloads, 0);
});
