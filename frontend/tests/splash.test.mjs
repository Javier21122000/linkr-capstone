// Splash di avvio e overlay di caricamento della mappa: quando compaiono e quando se ne vanno.
import assert from "node:assert/strict";
import { test } from "node:test";

import { createBootStore, isBootReady, SPLASH_EXIT_MS, SPLASH_HINT_MS, SPLASH_MAX_MS } from "../src/lib/appBoot.ts";
import { coversMap, hasRenderedOnce, mapLoadingMessage } from "../src/lib/mapLoading.ts";

test("avvio su Discover: servono router e prima risposta degli eventi; la mappa non c'entra", () => {
  assert.equal(isBootReady({ routerReady: false, discoverSettled: false, forced: false }, true), false);
  assert.equal(isBootReady({ routerReady: true, discoverSettled: false, forced: false }, true), false);
  assert.equal(isBootReady({ routerReady: false, discoverSettled: true, forced: false }, true), false);
  assert.equal(isBootReady({ routerReady: true, discoverSettled: true, forced: false }, true), true);
});

test("avvio su un'altra pagina: basta il router, Discover non si aspetta", () => {
  assert.equal(isBootReady({ routerReady: true, discoverSettled: false, forced: false }, false), true);
  assert.equal(isBootReady({ routerReady: false, discoverSettled: false, forced: false }, false), false);
});

test("il tempo massimo (o un errore di struttura) libera sempre lo splash", () => {
  assert.equal(isBootReady({ routerReady: false, discoverSettled: false, forced: true }, true), true);
  assert.ok(SPLASH_MAX_MS <= 10_000 && SPLASH_MAX_MS > SPLASH_HINT_MS, "mai uno splash indefinito; il suggerimento viene prima");
  assert.ok(SPLASH_EXIT_MS <= 300);
});

test("lo store avvisa una sola volta, ignora i segnali ripetuti e non torna indietro", () => {
  const store = createBootStore(true);
  let calls = 0;
  const unsubscribe = store.subscribe(() => { calls += 1; });
  assert.equal(store.getReady(), false);
  store.mark({ routerReady: true });
  assert.equal(calls, 0, "ancora in attesa degli eventi");
  store.mark({ discoverSettled: true });
  assert.equal(store.getReady(), true);
  assert.equal(calls, 1);
  store.mark({ discoverSettled: true });
  store.mark({ forced: true });
  assert.equal(calls, 1);
  assert.equal(store.getReady(), true);
  unsubscribe();
  assert.equal(createBootStore(false).getReady(), false);
});

test("overlay mappa: copre solo il primo caricamento, mai dopo che la mappa ha disegnato", () => {
  assert.equal(coversMap("loading", false), true);
  assert.equal(coversMap("recovering", false), true, "tentativi automatici del primo caricamento");
  assert.equal(coversMap("ready", false), false);
  assert.equal(coversMap("loading", true), false, "mappa ricreata dopo aver disegnato: pillola, non overlay");
  assert.equal(coversMap("recovering", true), false);
});

test("overlay mappa: errore e chiave mancante hanno la loro schermata; incompleta = ha disegnato, con avviso", () => {
  for (const status of ["error", "missing-key"]) assert.equal(coversMap(status, false), false);
  assert.equal(coversMap("degraded", false), false);
  assert.equal(hasRenderedOnce("ready"), true);
  assert.equal(hasRenderedOnce("degraded"), true);
  for (const status of ["loading", "recovering", "error", "missing-key"]) assert.equal(hasRenderedOnce(status), false);
});

test("testi dell'overlay: dicono cosa succede e offrono di riprovare solo quando è lenta", () => {
  assert.equal(mapLoadingMessage("loading", false), "Caricamento mappa");
  assert.equal(mapLoadingMessage("recovering", false), "Ricarico la mappa…");
  assert.match(mapLoadingMessage("loading", true), /più del previsto/);
});

test("CSS dello splash: movimento ridotto rispettato, una sola animazione infinita (il puntino), solo proprietà economiche", async () => {
  const { readFile } = await import("node:fs/promises");
  const css = await readFile(new URL("../src/styles/splash.css", import.meta.url), "utf8");
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  const infinite = css.match(/[^;{}]*\binfinite\b[^;{}]*/g) ?? [];
  assert.equal(infinite.length, 2, "impulso del puntino: variante screen e variante panel");
  for (const line of infinite) assert.match(line, /splash-pulse/);
  const keyframes = css.match(/@keyframes[^{]+\{[\s\S]*?\n\}/g) ?? [];
  for (const block of keyframes) assert.doesNotMatch(block, /\b(width|height|top|left|margin|padding)\s*:/, "niente layout nei fotogrammi");
});
