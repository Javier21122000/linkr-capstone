// La variante Urban Editorial Pop deve restare isolata: ogni regola vive sotto l'attributo `data-variant` (tranne il gancio inerte `.ed-deco`, che nel tema base nasconde le decorazioni).
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const strip = (source) => source.replace(/\/\*[\s\S]*?\*\//g, "");
const read = (name) => strip(readFileSync(new URL(`../src/styles/${name}`, import.meta.url), "utf8"));
const css = `${read("urban-editorial.css")}\n${read("editorial.css")}`;

/** Selettori di primo livello, anche dentro @media (ma non i passi di @keyframes). */
function selectors(source) {
  const result = [];
  let depth = 0;
  let buffer = "";
  const stack = [];
  for (const char of source) {
    if (char === "{") {
      const head = buffer.trim();
      stack.push(head);
      if (!head.startsWith("@") && !stack.slice(0, -1).some((h) => h.startsWith("@keyframes"))) result.push(head);
      buffer = "";
      depth += 1;
    } else if (char === "}") {
      stack.pop();
      depth -= 1;
      buffer = "";
    } else if (char === ";" && depth === 0) {
      buffer = "";
    } else {
      buffer += char;
    }
  }
  return result.flatMap((group) => group.split(",").map((s) => s.trim()));
}

test("ogni selettore della variante è dentro data-variant (o è il gancio .ed-deco)", () => {
  const all = selectors(css);
  assert.ok(all.length > 20);
  const loose = all.filter((selector) => !selector.startsWith('html[data-variant="urban-editorial"]') && selector !== ".ed-deco");
  assert.deepEqual(loose, []);
});

test("la variante si attiva con un solo attributo in index.html e il CSS non tocca la mappa o i controlli", () => {
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  assert.match(html, /<html[^>]*data-variant="urban-editorial"/);
  assert.doesNotMatch(css, /maplibregl|\.event-map__canvas|\.discover-filter/);
});

test("nessuna decorazione intercetta i clic, nel tema base è nascosta e il movimento ridotto è rispettato", () => {
  assert.match(css, /prefers-reduced-motion: reduce/);
  assert.match(css, /(^|\n)\.ed-deco \{\s*display: none;/);
  const shown = css.match(/html\[data-variant="urban-editorial"\] \.ed-deco \{[^}]*\}/);
  assert.ok(shown, "regola che mostra le decorazioni nella variante");
  assert.match(shown[0], /pointer-events: none/);
});

test("i componenti editoriali segnano ogni decorazione come nascosta ai lettori di schermo", () => {
  const dir = new URL("../src/components/editorial/", import.meta.url);
  for (const file of ["EditorialIllustration.tsx", "EditorialSticker.tsx", "HalftoneAccent.tsx", "scenes.tsx", "CutoutWord.tsx"]) {
    const source = readFileSync(new URL(file, dir), "utf8");
    for (const tag of source.match(/<(svg|span|div)[^>]*ed-deco[^>]*>/g) ?? []) assert.match(tag, /aria-hidden="true"/, `${file}: ${tag}`);
  }
});

test("il collage fotografico è decorativo, ha dimensioni esplicite e non si carica nel tema base", () => {
  const source = readFileSync(new URL("../src/components/editorial/scenes.tsx", import.meta.url), "utf8");
  assert.match(source, /<picture className="ed-deco ed-hero__art">/);
  assert.match(source, /<source srcSet=\{PEOPLE_HERO\.webp\} type="image\/webp" \/>/);
  const img = source.match(/<img src=\{PEOPLE_HERO\.src\}[^>]*>/)?.[0] ?? "";
  assert.match(img, /alt=""/);
  assert.match(img, /width=\{PEOPLE_HERO\.width\}/);
  assert.match(img, /height=\{PEOPLE_HERO\.height\}/);
  assert.match(source, /dataset\.variant !== "urban-editorial"\) return null/);
  assert.match(source, /1153/);
  assert.match(source, /1364/);
});

test("i collage dell'onboarding sono decorativi, con WebP + PNG di ripiego e dimensioni esplicite", () => {
  const source = readFileSync(new URL("../src/components/editorial/scenes.tsx", import.meta.url), "utf8");
  const block = source.slice(source.indexOf("ONBOARDING_PHOTOS"), source.indexOf("export function ProfileArt") === -1 ? undefined : source.indexOf("export function ProfileArt"));
  for (const name of ["people-hero", "connect-hero", "live-hero"]) assert.match(block, new RegExp(`/images/editorial/${name}\\.png`));
  assert.match(block, /<source srcSet=\{photo\.webp\} type="image\/webp" \/>/);
  assert.match(block, /<img src=\{photo\.src\} width=\{photo\.width\} height=\{photo\.height\} alt=""/);
  assert.match(block, /dataset\.variant === "urban-editorial"/);
  for (const [file, size] of [["people-hero-768.webp", 768], ["connect-hero-960.webp", 960], ["live-hero-768.webp", 768]]) {
    const bytes = readFileSync(new URL(`../public/images/editorial/${file}`, import.meta.url));
    assert.equal(bytes.toString("ascii", 0, 4), "RIFF");
    assert.equal(bytes.toString("ascii", 8, 12), "WEBP");
    assert.ok(bytes.length < 400_000, `${file} pesa ${bytes.length}`);
    assert.ok(size > 0);
  }
});

test("il lettering ritagliato mantiene il testo vero nel DOM", () => {
  const source = readFileSync(new URL("../src/components/editorial/CutoutWord.tsx", import.meta.url), "utf8");
  assert.match(source, /ed-cutout__plain">\{word\}/);
  assert.match(css, /\.ed-cutout__plain \{[^}]*clip-path: inset\(50%\)/);
});
