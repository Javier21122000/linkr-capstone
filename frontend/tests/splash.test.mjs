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

test("CSS dello splash: movimento ridotto rispettato, solo le due mani del loader girano all'infinito, solo proprietà economiche", async () => {
  const { readFile } = await import("node:fs/promises");
  const css = await readFile(new URL("../src/styles/splash.css", import.meta.url), "utf8");
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  const infinite = css.match(/[^;{}]*\binfinite\b[^;{}]*/g) ?? [];
  assert.equal(infinite.length, 2, "avvicinamento delle mani: una animazione per mano, solo nel loader");
  for (const line of infinite) assert.match(line, /splash-hands-/);
  const keyframes = css.match(/@keyframes[^{]+\{[\s\S]*?\n\}/g) ?? [];
  for (const block of keyframes) assert.doesNotMatch(block, /\b(width|height|top|left|margin|padding)\s*:/, "niente layout nei fotogrammi");
});

test("CSS dello splash: durata dell'introduzione tra 0,9 e 1,2 s e ciclo del loader di circa 1,4 s", async () => {
  const { readFile } = await import("node:fs/promises");
  const css = await readFile(new URL("../src/styles/splash.css", import.meta.url), "utf8");
  const toMs = (v, unit) => (unit === "s" ? Number(v) * 1000 : Number(v));
  const anim = (name) => {
    const m = css.match(new RegExp(`animation:\\s*${name}\\s+([\\d.]+)(ms|s)(?:[^;]*?\\s([\\d.]+)(ms|s))?`));
    assert.ok(m, `animazione ${name} dichiarata`);
    return { duration: toMs(m[1], m[2]), delay: m[3] ? toMs(m[3], m[4]) : 0 };
  };
  const hand = anim("splash-hand-top");
  const word = anim("splash-word-in");
  const total = Math.max(hand.delay + hand.duration, word.delay + word.duration);
  assert.ok(total >= 900 && total <= 1200, `introduzione di ${total} ms`);
  assert.equal(anim("splash-hands-top").duration, 1400);
  assert.equal(anim("splash-hands-bottom").duration, 1400);
});

test("logo: tracciati vettoriali, nessun font né immagine incorporati e nessun testo SVG", async () => {
  const { readFile } = await import("node:fs/promises");
  const read = (p) => readFile(new URL(p, import.meta.url), "utf8");
  const paths = await read("../src/components/brand/brandPaths.ts");
  assert.match(paths, /HAND_PATH\s*=\s*"M/);
  assert.match(paths, /WORDMARK_PATH\s*=\s*"M/);
  for (const file of ["../src/components/brand/LinkrLogo.tsx", "../src/components/ui/LinkrSplash.tsx", "../src/components/ui/BrandMark.tsx"]) {
    const src = await read(file);
    assert.doesNotMatch(src, /<text\b|<image\b|font-family|@font-face/, file);
  }
  for (const file of ["../public/brand/linkr-logo.svg", "../public/linkr-symbol.svg", "../public/favicon.svg"]) {
    const svg = await read(file);
    assert.match(svg, /<path /, file);
    assert.doesNotMatch(svg, /<text\b|<image\b|font-family|href=/, file);
  }
});

test("PWA: manifest con nome, colori e icone esistenti (PNG con le dimensioni dichiarate); head collegato", async () => {
  const { readFile } = await import("node:fs/promises");
  const manifest = JSON.parse(await readFile(new URL("../public/manifest.webmanifest", import.meta.url), "utf8"));
  assert.equal(manifest.name, "LINKR");
  assert.equal(manifest.display, "standalone");
  assert.equal(manifest.background_color, "#f3eee2");
  assert.ok(manifest.icons.some((i) => i.purpose === "maskable"), "icona maskable");
  for (const icon of manifest.icons) {
    const buffer = await readFile(new URL(`../public${icon.src}`, import.meta.url));
    assert.equal(buffer.subarray(1, 4).toString(), "PNG", icon.src);
    const [w, h] = icon.sizes.split("x").map(Number);
    assert.equal(buffer.readUInt32BE(16), w, `${icon.src} larghezza`);
    assert.equal(buffer.readUInt32BE(20), h, `${icon.src} altezza`);
  }
  const apple = await readFile(new URL("../public/apple-touch-icon.png", import.meta.url));
  assert.equal(apple.readUInt32BE(16), 180);
  const html = await readFile(new URL("../index.html", import.meta.url), "utf8");
  for (const needle of ['rel="manifest"', 'rel="apple-touch-icon"', 'href="/favicon.svg"', "#f3eee2"]) assert.ok(html.includes(needle), needle);
});
