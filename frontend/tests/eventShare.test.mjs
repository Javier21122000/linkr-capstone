// Condivisione evento: indirizzo pubblico, testo dell'invito, Web Share API, ripiego sulla copia e ambienti senza API.
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  browserShareEnvironment,
  buildEventShareUrl,
  buildSharePayload,
  isShareCancellation,
  resolveShareOrigin,
  shareEvent,
  toPublicOrigin,
} from "../src/lib/eventShare.ts";

const ID = "018F0000-0000-7000-8000-000000000002";
const SITE = "https://linkr-web.onrender.com";
const payload = buildSharePayload({ title: "  Aperitivo sui Navigli ", when: "ven 6 nov · 19:30–21:30", where: "Bar QA · Navigli", url: `${SITE}/events/${ID.toLowerCase()}` });
const abort = () => Object.assign(new Error("share canceled"), { name: "AbortError" });

// ——— Indirizzo ———
test("l'indirizzo dell'invito è assoluto, canonico e porta solo l'id dell'evento", () => {
  assert.equal(buildEventShareUrl(SITE, ID), `${SITE}/events/${ID.toLowerCase()}`);
  // un'origine con percorso, query o hash non finisce nell'invito: resta solo l'origine
  assert.equal(buildEventShareUrl(`${SITE}/profile?token=abc#x`, ID), `${SITE}/events/${ID.toLowerCase()}`);
  assert.equal(new URL(buildEventShareUrl(SITE, ID)).search, "");
});

test("niente invito senza un indirizzo pubblico: localhost, IP, http e nomi interni non si condividono", () => {
  for (const origin of ["http://localhost:5173", "https://localhost", "http://127.0.0.1:5173", "https://192.168.1.20", "http://linkr-web.onrender.com", "https://mac.local", "https://intranet", "https://app.test", "https://[::1]", "not a url", "", null, undefined, "javascript:alert(1)", "https://user:pw@linkr-web.onrender.com"]) {
    assert.equal(toPublicOrigin(origin), null, String(origin));
    assert.equal(buildEventShareUrl(origin, ID), null, String(origin));
  }
});

test("un id che non è un UUID non diventa un indirizzo (niente percorsi arbitrari)", () => {
  for (const id of ["", "123", "../admin", `${ID}/../x`, `${ID}?a=1`, "javascript:alert(1)"]) assert.equal(buildEventShareUrl(SITE, id), null, id);
});

test("l'origine configurata per il sito vince, se è pubblica; altrimenti vale quella da cui l'app è aperta", () => {
  assert.equal(resolveShareOrigin("https://linkr.example.org", SITE), "https://linkr.example.org");
  assert.equal(resolveShareOrigin("", SITE), SITE);
  assert.equal(resolveShareOrigin(undefined, SITE), SITE);
  assert.equal(resolveShareOrigin("http://localhost:3000", SITE), SITE, "una configurazione non pubblica non è usata");
  assert.equal(resolveShareOrigin(undefined, "http://localhost:5173"), null, "in locale, senza configurazione, nessun invito");
});

// ——— Testo ———
test("il messaggio contiene titolo, quando, dove, l'indirizzo e nessun dato di chi condivide", () => {
  assert.equal(payload.title, "Aperitivo sui Navigli");
  assert.match(payload.text, /^Aperitivo sui Navigli\nven 6 nov · 19:30–21:30 · Bar QA · Navigli\n/);
  assert.equal(payload.url, `${SITE}/events/${ID.toLowerCase()}`);
  assert.doesNotMatch(JSON.stringify(payload), /token|session|@|localhost/i);
});

// ——— Invio ———
test("con la Web Share API il foglio di sistema riceve titolo, testo e indirizzo", async () => {
  const calls = [];
  const outcome = await shareEvent(payload, { share: async (data) => { calls.push(data); }, writeClipboard: async () => assert.fail("non deve copiare") });
  assert.equal(outcome, "shared");
  assert.deepEqual(calls, [payload]);
});

test("chiudere il foglio di condivisione non è un errore e non copia niente", async () => {
  assert.equal(isShareCancellation(abort()), true);
  assert.equal(isShareCancellation(new Error("x")), false);
  const outcome = await shareEvent(payload, { share: async () => { throw abort(); }, writeClipboard: async () => assert.fail("non deve copiare") });
  assert.equal(outcome, "cancelled");
});

test("share viene chiamato subito, nello stesso gesto: nessuna attesa prima", () => {
  let called = false;
  void shareEvent(payload, { share: async () => { called = true; } });
  assert.equal(called, true, "chiamato in modo sincrono (Safari lo accetta solo dentro il gesto dell'utente)");
});

test("senza Web Share API (browser desktop) si copia il link", async () => {
  const copied = [];
  const outcome = await shareEvent(payload, { writeClipboard: async (text) => { copied.push(text); } });
  assert.equal(outcome, "copied");
  assert.deepEqual(copied, [payload.url], "si copia solo l'indirizzo, non il testo");
});

test("se canShare dice di no, o il sistema rifiuta per altro, si ripiega sulla copia", async () => {
  const copied = [];
  const env = { writeClipboard: async (text) => { copied.push(text); } };
  assert.equal(await shareEvent(payload, { ...env, share: async () => assert.fail("non va chiamato"), canShare: () => false }), "copied");
  assert.equal(await shareEvent(payload, { ...env, share: async () => { throw Object.assign(new Error("denied"), { name: "NotAllowedError" }); } }), "copied");
  assert.deepEqual(copied, [payload.url, payload.url]);
});

test("se la Clipboard API non c'è o rifiuta, si prova il ripiego sincrono", async () => {
  const copied = [];
  const legacyCopy = (text) => { copied.push(text); return true; };
  assert.equal(await shareEvent(payload, { legacyCopy }), "copied");
  assert.equal(await shareEvent(payload, { writeClipboard: async () => { throw new Error("denied"); }, legacyCopy }), "copied");
  assert.deepEqual(copied, [payload.url, payload.url]);
});

test("se nessuna copia riesce l'esito è 'failed' (l'interfaccia mostra il link da copiare a mano), mai un'eccezione", async () => {
  assert.equal(await shareEvent(payload, {}), "failed");
  assert.equal(await shareEvent(payload, { writeClipboard: async () => { throw new Error("denied"); }, legacyCopy: () => false }), "failed");
  assert.equal(await shareEvent(payload, { legacyCopy: () => { throw new Error("boom"); } }), "failed");
});

// ——— Ambiente reale ———
test("browser senza Web Share API né Clipboard API: nessuna funzione offerta, nessun errore", async () => {
  const env = browserShareEnvironment({}, undefined);
  assert.equal(env.share, undefined);
  assert.equal(env.canShare, undefined);
  assert.equal(env.writeClipboard, undefined);
  assert.equal(env.legacyCopy, undefined);
  assert.equal(await shareEvent(payload, env), "failed");
  assert.equal(browserShareEnvironment(undefined, undefined).share, undefined);
});

test("browser con Web Share API: usa navigator.share con i dati dell'invito", async () => {
  const seen = [];
  const nav = { share: async (data) => { seen.push(data); }, canShare: () => true, clipboard: { writeText: async () => {} } };
  assert.equal(await shareEvent(payload, browserShareEnvironment(nav, undefined)), "shared");
  assert.deepEqual(seen, [payload]);
});
