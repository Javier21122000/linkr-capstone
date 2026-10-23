// Dettaglio evento su mobile: il testo che scorre non si legge attraverso la Bottom Navigation e il padding inferiore non cambia.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const css = readFileSync(new URL("../src/styles/pages.css", import.meta.url), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const page = readFileSync(new URL("../src/pages/EventDetailPage.tsx", import.meta.url), "utf8");

/** Il contenuto del primo blocco `@media (max-width: 47.999rem) { … }` che contiene `needle`. */
function mobileBlock(needle) {
  const start = css.indexOf("@media (max-width: 47.999rem)");
  assert.ok(start >= 0);
  let depth = 0;
  for (let i = css.indexOf("{", start); i < css.length; i++) {
    if (css[i] === "{") depth++;
    if (css[i] === "}" && --depth === 0) return css.slice(start, i + 1);
  }
  throw new Error(needle);
}

test("la sfumatura sotto la Bottom Navigation è sticky (non fixed: l'animazione d'ingresso la sposterebbe), non cliccabile e solo su mobile", () => {
  const block = mobileBlock(".event-page__scrim");
  assert.match(block, /\.event-page__scrim\s*\{[^}]*position:\s*sticky/);
  assert.match(block, /\.event-page__scrim\s*\{[^}]*pointer-events:\s*none/);
  assert.match(block, /\.event-page__scrim\s*\{[^}]*bottom:\s*0/);
  assert.doesNotMatch(block, /position:\s*fixed/);
  // fuori dal blocco mobile la sfumatura e il distanziatore non esistono
  assert.match(css, /\.event-page__spacer,\s*\.event-page__scrim\s*\{\s*display:\s*none/);
});

test("il margine negativo della sfumatura è uguale alla sua altezza: nessuna altezza aggiunta alla pagina", () => {
  const block = mobileBlock(".event-page__scrim");
  assert.match(block, /height:\s*var\(--event-scrim-h\)/);
  assert.match(block, /margin-top:\s*calc\(-1 \* var\(--event-scrim-h\)\)/);
});

test("il padding inferiore è lo stesso di prima (7.25rem + safe area), ora portato dal distanziatore", () => {
  const block = mobileBlock(".event-page__spacer");
  assert.match(block, /\.event-page\.page-with-mobile-nav\s*\{\s*padding-bottom:\s*0/);
  assert.match(block, /\.event-page__spacer\s*\{[^}]*height:\s*calc\(7\.25rem \+ env\(safe-area-inset-bottom\)\)/);
});

test("la pagina monta distanziatore e sfumatura in fondo, nascosti ai lettori di schermo", () => {
  assert.match(page, /className="page-with-mobile-nav event-page"/);
  assert.match(page, /<div className="event-page__spacer" aria-hidden="true" \/>\s*<div className="event-page__scrim" aria-hidden="true" \/>/);
});
