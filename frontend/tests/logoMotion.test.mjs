// Movimento del logo: ciclo apri/chiudi (splash e loader), uscita dello splash senza interrompere il ciclo, giro del logo in header.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { createFlagStore } from "../src/lib/appBoot.ts";
import {
  isFirstSplashRun,
  markSplashSeen,
  SPLASH_CYCLE_MS,
  SPLASH_HEAD_HOLD,
  SPLASH_REST_FROM,
  SPLASH_RETURN_DELAY_MS,
  splashExitDelayMs,
} from "../src/lib/splashCycle.ts";

/** Spostamenti in % dell'altezza del logo (1447 unità del disegno); a 158 unità le dita si toccano (spazio centrale 316). */
const CONTACT = (158 / 1447) * 100;
const read = (path) => readFile(new URL(path, import.meta.url), "utf8");

/** Fotogrammi di una @keyframes: [{ at: "0%, 10%", body: "..." }]. */
function frames(css, name) {
  const block = css.match(new RegExp(`@keyframes ${name} \\{([\\s\\S]*?)\\n\\}`));
  assert.ok(block, `@keyframes ${name}`);
  return [...block[1].matchAll(/([\d.%, ]+)\{([^}]*)\}/g)].map((m) => ({ at: m[1].trim(), body: m[2] }));
}
const lastPct = (at) => Number(at.split(",").pop().replace("%", ""));

test("CSS del logo: movimento ridotto rispettato, un solo ciclo ripetuto (mani e wordmark), solo proprietà economiche", async () => {
  const css = await read("../src/styles/splash.css");
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  const infinite = css.match(/[^;{}]*\binfinite\b[^;{}]*/g) ?? [];
  assert.equal(infinite.length, 3, "una animazione per mano e una per il wordmark");
  for (const line of infinite) assert.match(line, /linkr-(hand-top|hand-bottom|word)\s+var\(--linkr-cycle\)/);
  for (const block of css.match(/@keyframes[^{]+\{[\s\S]*?\n\}/g) ?? []) {
    assert.doesNotMatch(block, /\b(width|height|top|left|margin|padding)\s*:/, "niente layout nei fotogrammi");
  }
  const reduced = css.slice(css.indexOf("@media (prefers-reduced-motion: reduce)"));
  assert.match(reduced, /animation:\s*none\s*!important/);
  assert.match(reduced, /opacity:\s*1\s*!important/, "wordmark sempre leggibile");
});

test("CSS del logo: ciclo di 1,6 s come la costante dell'uscita; mani speculari, wordmark nascosto a spazio chiuso", async () => {
  const css = await read("../src/styles/splash.css");
  assert.equal(Number(css.match(/--linkr-cycle:\s*(\d+)ms/)[1]), SPLASH_CYCLE_MS);
  assert.ok(SPLASH_CYCLE_MS >= 1400 && SPLASH_CYCLE_MS <= 1800, "ciclo indicativo di 1,4–1,8 s");

  const top = frames(css, "linkr-hand-top");
  const bottom = frames(css, "linkr-hand-bottom");
  const shift = (body) => Number(body.match(/translateY\((-?[\d.]+)(?:%)?\)/)[1]);
  assert.equal(top.length, bottom.length);
  top.forEach((frame, i) => assert.equal(shift(frame.body), 0 - shift(bottom[i].body), `${frame.at}: mani speculari, logo sempre centrato`));
  assert.equal(shift(top[0].body), 0, "parte aperto");
  assert.equal(shift(top.at(-1).body), 0, "finisce aperto come inizia: il ciclo si ripete senza salti");
  assert.ok(Math.max(...top.map((f) => shift(f.body))) > CONTACT, "oltre il contatto (spazio centrale 316 unità = 21,8 % dell'altezza): compressione");

  const word = frames(css, "linkr-word");
  assert.match(word[0].body, /opacity:\s*1/, "wordmark leggibile all'inizio");
  assert.match(word.at(-1).body, /opacity:\s*1/, "e alla fine");
  const hiddenAt = word.filter((f) => /opacity:\s*0\b/.test(f.body)).map((f) => lastPct(f.at));
  const closedAt = top.filter((f) => shift(f.body) >= CONTACT).map((f) => lastPct(f.at));
  assert.ok(hiddenAt.length >= 2);
  assert.ok(Math.min(...hiddenAt) < Math.min(...closedAt), "il wordmark è sparito prima che le mani si tocchino");
  assert.ok(Math.max(...hiddenAt) > Math.max(...closedAt), "e ricompare dopo l'inizio della riapertura");
  assert.ok(Math.max(...hiddenAt) < SPLASH_REST_FROM * 100, "ma prima del riposo finale");
});

test("uscita dello splash: primo avvio = almeno un ciclo intero, poi attende le mani aperte; mai a metà chiusura", () => {
  const rest = SPLASH_REST_FROM * SPLASH_CYCLE_MS;
  assert.equal(splashExitDelayMs(20, true), Math.round(rest - 20), "app prontissima: si completa comunque il ciclo");
  assert.equal(splashExitDelayMs(600, true), Math.round(rest - 600), "a metà chiusura: si aspetta la riapertura");
  assert.equal(splashExitDelayMs(rest, true), 0, "mani riaperte: esce subito");
  assert.equal(splashExitDelayMs(SPLASH_CYCLE_MS - 10, true), 0, "nella sosta finale aperta");
  assert.equal(splashExitDelayMs(SPLASH_CYCLE_MS + 40, true), 0, "dal secondo ciclo anche la sosta iniziale è a mani aperte");
  assert.equal(splashExitDelayMs(SPLASH_CYCLE_MS + 700, true), Math.round(rest - 700), "caricamento lento: si attende la fine del ciclo in corso");
  for (let t = 0; t < 6000; t += 37) {
    const wait = splashExitDelayMs(t, true);
    const at = (t + wait) % SPLASH_CYCLE_MS;
    assert.ok(at >= rest - 1 || at < SPLASH_HEAD_HOLD * SPLASH_CYCLE_MS, `t=${t}: esce a mani aperte (posizione ${at})`);
    assert.ok(t + wait >= rest - 1, `t=${t}: al primo avvio mai prima di un ciclo completo`);
    assert.ok(wait <= rest, `t=${t}: attesa mai oltre un ciclo`);
  }
});

test("uscita dello splash: avvii successivi, esce subito se pronta prima del movimento o nella sosta iniziale", () => {
  assert.equal(splashExitDelayMs(0, false), 0);
  assert.equal(splashExitDelayMs(SPLASH_RETURN_DELAY_MS - 1, false), 0, "il movimento non è ancora partito");
  assert.equal(splashExitDelayMs(SPLASH_RETURN_DELAY_MS + 50, false), 0, "sosta iniziale a mani aperte");
  const wait = splashExitDelayMs(SPLASH_RETURN_DELAY_MS + 600, false);
  assert.equal(wait, Math.round(SPLASH_REST_FROM * SPLASH_CYCLE_MS - 600));
  assert.ok(wait < SPLASH_CYCLE_MS, "attesa mai oltre un ciclo");
});

test("primo avvio sul dispositivo: riconosciuto dalla chiave, segnato a fine splash; senza storage si va rapidi", () => {
  const data = new Map();
  const storage = { getItem: (k) => data.get(k) ?? null, setItem: (k, v) => void data.set(k, v) };
  assert.equal(isFirstSplashRun(storage), true);
  markSplashSeen(storage);
  assert.equal(isFirstSplashRun(storage), false);
  assert.equal(isFirstSplashRun(null), false);
  const broken = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); } };
  assert.equal(isFirstSplashRun(broken), false);
  assert.doesNotThrow(() => markSplashSeen(broken));
  assert.doesNotThrow(() => markSplashSeen(null));
});

test("segnale «splash uscito»: si accende una volta e avvisa chi ascolta una sola volta", () => {
  const flag = createFlagStore();
  let calls = 0;
  const off = flag.subscribe(() => { calls += 1; });
  assert.equal(flag.get(), false);
  flag.raise();
  flag.raise();
  assert.equal(flag.get(), true);
  assert.equal(calls, 1);
  off();
});

test("loader della mappa e splash usano lo stesso logo con wordmark; l'onboarding aspetta l'uscita dello splash", async () => {
  const splash = await read("../src/components/ui/LinkrSplash.tsx");
  assert.match(splash, /<LinkrLockup/);
  assert.doesNotMatch(splash, /<LinkrHands/);
  const guidance = await read("../src/context/ProductGuidanceContext.tsx");
  assert.match(guidance, /useSplashGone\(\)/);
  assert.doesNotMatch(guidance, /useBootReady\(\)/);
  const boot = await read("../src/components/ui/BootSplash.tsx");
  assert.match(boot, /splashExitDelayMs\(/);
  assert.match(boot, /splashGone\.raise\(\)/);
});

test("easter egg del logo: gira solo il simbolo delle mani, solo su Discover, non naviga, non si accumula e rispetta il movimento ridotto", async () => {
  const header = await read("../src/components/layout/AppHeader.tsx");
  assert.match(header, /pathname !== "\/"/, "fuori da Discover resta la navigazione normale");
  assert.match(header, /event\.preventDefault\(\)/);
  assert.match(header, /if \(logoSpinning \|\| prefersReducedMotion\(\)\) return;/, "nessun giro accumulato né con movimento ridotto");
  assert.match(header, /onAnimationEnd=/);
  assert.match(header, /event\.animationName !== "brand-spin"/, "il giro finisce quando finisce l'animazione del simbolo");
  assert.match(await read("../src/components/ui/BrandMark.tsx"), /<LinkrSymbol className="brand-mark__symbol /, "il simbolo è l'elemento che gira");
  const css = await read("../src/styles/layout.css");
  assert.match(css, /\.app-header__logo\[data-spinning\] \.brand-mark__symbol \{[^}]*animation:\s*brand-spin/, "gira il solo simbolo: né il wordmark né la pillola né il contenitore");
  assert.doesNotMatch(css, /\.app-header__logo\[data-spinning\]\s*\{[^}]*animation/);
  const spin = css.match(/animation:\s*brand-spin\s+(\d+)ms/);
  assert.ok(spin && Number(spin[1]) >= 450 && Number(spin[1]) <= 600, "giro di 450–600 ms");
  assert.match(css, /@keyframes brand-spin\s*\{\s*to\s*\{\s*transform:\s*rotate\(360deg\)/);
  const afterSpin = css.slice(css.indexOf("@media (prefers-reduced-motion: reduce)", css.indexOf("brand-spin")));
  assert.match(afterSpin, /app-header__logo\[data-spinning\] \.brand-mark__symbol[\s\S]*?animation:\s*none/);
});

test("animazioni sul compositore: strati HTML con transform/opacity, mai gruppi SVG; nessun will-change indiscriminato", async () => {
  const logo = await read("../src/components/brand/LinkrLogo.tsx");
  const lockup = logo.slice(logo.indexOf("export function LinkrLockup"));
  assert.equal((lockup.match(/className="linkr-layer /g) ?? []).length, 3, "mano in basso, mano in alto, wordmark");
  assert.match(lockup, /<span className="linkr-layer linkr-hand linkr-hand--bottom">/);
  assert.doesNotMatch(logo.slice(0, logo.indexOf("export function LinkrLockup")), /linkr-hand|linkr-word/, "il simbolo statico non ha classi animate");
  const css = await read("../src/styles/splash.css");
  assert.doesNotMatch(css, /translateY\(-?[\d.]+px\)/, "spostamenti in % dell'altezza dello strato, non in px del disegno");
  assert.doesNotMatch(css, /transform-box/, "niente trasformazioni su gruppi SVG");
  assert.doesNotMatch(css, /will-change/);
  assert.doesNotMatch(css, /filter\s*:/, "niente filtri");
  // 170 / 1447 = 11,7484 %, 186 / 1447 = 12,8542 %, 12 / 1447 = 0,8293 %
  const values = new Set([...css.matchAll(/translateY\((-?[\d.]+)%\)/g)].map((m) => Math.abs(Number(m[1]))));
  for (const [units, pct] of [[170, 11.7484], [186, 12.8542], [12, 0.8293]]) {
    assert.ok(values.has(pct), `${units} unità = ${pct} %`);
    assert.ok(Math.abs((units / 1447) * 100 - pct) < 0.0001);
  }
});
