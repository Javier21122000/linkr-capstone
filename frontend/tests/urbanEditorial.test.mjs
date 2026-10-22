// La variante Urban Editorial Pop deve restare isolata: ogni regola vive sotto l'attributo `data-variant` (tranne il gancio inerte `.ed-slot`).
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const css = readFileSync(new URL("../src/styles/urban-editorial.css", import.meta.url), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

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

test("ogni selettore della variante è dentro data-variant (o è il gancio .ed-slot)", () => {
  const all = selectors(css);
  assert.ok(all.length > 20);
  const loose = all.filter((selector) => !selector.startsWith('html[data-variant="urban-editorial"]') && selector !== ".ed-slot");
  assert.deepEqual(loose, []);
});

test("la variante si attiva con un solo attributo in index.html e il CSS non tocca la mappa o i controlli", () => {
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  assert.match(html, /<html[^>]*data-variant="urban-editorial"/);
  assert.doesNotMatch(css, /maplibregl|\.event-map__canvas|\.discover-filter/);
});

test("nessuna decorazione intercetta i clic e il movimento ridotto è rispettato", () => {
  assert.match(css, /prefers-reduced-motion: reduce/);
  const slots = css.match(/\.ed-slot\[data-slot="[a-z-]+"\][^{]*\{[^}]*\}/g) ?? [];
  const placed = slots.filter((rule) => /position: absolute/.test(rule));
  assert.equal(placed.length, 2);
  for (const rule of placed) assert.match(rule, /pointer-events: none/);
});
