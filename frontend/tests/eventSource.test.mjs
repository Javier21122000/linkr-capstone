// Origine degli eventi importati: nome da mostrare e link "Dettagli ufficiali" (solo https sicuri).
import assert from "node:assert/strict";
import { test } from "node:test";

import { officialDetailsUrl, sourceLabel } from "../src/lib/eventSource.ts";

const tm = (url) => ({ provider: "TICKETMASTER", url });

test("gli eventi importati da Ticketmaster hanno l'etichetta, gli altri no", () => {
  assert.equal(sourceLabel(tm("https://www.ticketmaster.it/event/1")), "Ticketmaster");
  assert.equal(sourceLabel(null), null);
  assert.equal(sourceLabel(undefined), null);
  assert.equal(sourceLabel({ provider: "SOMETHING_ELSE", url: null }), null);
});

test("un link https normale è accettato", () => {
  assert.equal(officialDetailsUrl(tm("https://www.ticketmaster.it/event/123?utm=x")), "https://www.ticketmaster.it/event/123?utm=x");
  assert.equal(officialDetailsUrl(tm("https://www.ticketone.it/event/abc")), "https://www.ticketone.it/event/abc");
});

test("tutto ciò che non è un https sicuro non produce un link", () => {
  const unsafe = [
    null,
    "",
    "   ",
    "http://www.ticketmaster.it/event/1",
    "javascript:alert(1)",
    "JaVaScRiPt:alert(1)",
    "data:text/html,<script>alert(1)</script>",
    "vbscript:msgbox(1)",
    "//www.ticketmaster.it/event/1",
    "/event/1",
    "www.ticketmaster.it/event/1",
    "https://",
    "https://localhost/event/1",
    "https://user:password@www.ticketmaster.it/event/1",
    "https://user@www.ticketmaster.it/event/1",
    "ftp://www.ticketmaster.it/event/1",
    "not a url",
  ];
  for (const url of unsafe) {
    assert.equal(officialDetailsUrl(tm(url)), null, String(url));
  }
  assert.equal(officialDetailsUrl(null), null);
  assert.equal(officialDetailsUrl(undefined), null);
  assert.equal(officialDetailsUrl({ provider: "TICKETMASTER" }), null);
});
