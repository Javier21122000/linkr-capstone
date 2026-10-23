// Test della richiesta "Visualizza sulla mappa" e del ritorno dopo l'accesso.
import assert from "node:assert/strict";
import { test } from "node:test";

import { createBackState, createMapFocusState, getSafeReturnPath, readBackRequest, readMapFocusRequest, readReturnPath } from "../src/lib/navigation.ts";

const ID = "22222222-2222-4222-8222-222222222222";

test("la richiesta di mappa va e viene dallo stato di navigazione", () => {
  const state = createMapFocusState(ID, 1234);
  assert.deepEqual(readMapFocusRequest(state), { eventId: ID, requestedAt: 1234 });
});

test("stati estranei o malformati non sono richieste", () => {
  for (const state of [null, undefined, "x", {}, { from: {} }, { mapFocus: null }, { mapFocus: { eventId: "non-un-uuid", requestedAt: 1 } }, { mapFocus: { eventId: ID } }, { mapFocus: { eventId: ID, requestedAt: Number.NaN } }]) {
    assert.equal(readMapFocusRequest(state), null);
  }
});

test("un identificativo con caratteri strani non passa (niente iniezione nel selettore o nell'URL)", () => {
  assert.equal(readMapFocusRequest({ mapFocus: { eventId: `${ID}"><script>`, requestedAt: 1 } }), null);
  assert.equal(readMapFocusRequest({ mapFocus: { eventId: "../../etc/passwd", requestedAt: 1 } }), null);
});

test("percorso di ritorno dopo l'accesso resta sicuro", () => {
  assert.equal(getSafeReturnPath({ from: { pathname: "/events/abc", search: "?a=1", hash: "#x" } }), "/events/abc?a=1#x");
  assert.equal(getSafeReturnPath({ from: { pathname: "//evil.example" } }), "/");
  assert.equal(getSafeReturnPath({ from: { pathname: "/login" } }), "/");
  assert.equal(getSafeReturnPath(null), "/");
});

test("il ritorno contestuale viaggia come chiave di un elenco chiuso", () => {
  const request = readBackRequest(createBackState("admin-events"));
  assert.deepEqual(request, { context: "admin-events", target: { to: "/admin?section=events", label: "Admin · Eventi" }, viaDetail: false });
  assert.equal(readBackRequest(createBackState("admin-events", true))?.viaDetail, true);
});

test("senza contesto, o con un valore estraneo, il ritorno resta quello di sempre", () => {
  for (const state of [null, undefined, {}, "x", { backTo: "/admin" }, { backTo: "https://evil.example" }, { backTo: "__proto__" }, { backTo: "toString" }, { backTo: 3 }]) {
    assert.equal(readBackRequest(state), null);
  }
});

test("ritorno dopo l'accesso: nessun indirizzo esterno o malformato è un ritorno valido (open redirect)", () => {
  const hostile = [
    "//evil.example", "//evil.example/events/1", "/\\evil.example", "\\\\evil.example", "http://evil.example", "https://evil.example/x",
    "javascript:alert(1)", "events/abc", "", " /events/abc", "/events/abc\n//evil", "/events/\tabc", "/login", "/register",
  ];
  for (const pathname of hostile) {
    assert.equal(readReturnPath({ from: { pathname } }), null, JSON.stringify(pathname));
    assert.equal(getSafeReturnPath({ from: { pathname } }), "/", JSON.stringify(pathname));
  }
  for (const state of [undefined, null, 5, "x", {}, { from: null }, { from: 3 }, { from: { pathname: 4 } }, { from: {} }]) assert.equal(readReturnPath(state), null);
});

test("ritorno dopo l'accesso: search e hash validi si conservano, quelli malformati si scartano senza perdere il percorso", () => {
  assert.equal(readReturnPath({ from: { pathname: "/events/abc", search: "?a=1", hash: "#x" } }), "/events/abc?a=1#x");
  assert.equal(readReturnPath({ from: { pathname: "/events/abc", search: "a=1", hash: "x" } }), "/events/abc");
  assert.equal(readReturnPath({ from: { pathname: "/events/abc", search: "?a=1\n//evil", hash: "#a b" } }), "/events/abc");
});

test("invito: un visitatore anonimo che preme Partecipa torna all'evento originale dopo accesso o registrazione", () => {
  const EVENT = "/events/018f0000-0000-7000-8000-000000000002";
  // lo stato che mettono `EventParticipationPanel` e `EventPreview` è la posizione del router; Login e Register la leggono con la stessa funzione
  const fromRouter = { from: { pathname: EVENT, search: "", hash: "", state: null, key: "k1" } };
  assert.equal(readReturnPath(fromRouter), EVENT);
  assert.equal(getSafeReturnPath(fromRouter), EVENT);
  // chi non arriva da nessuna pagina (apre /login direttamente) non ha un ritorno: la registrazione prosegue con la scelta degli interessi
  assert.equal(readReturnPath(null), null);
});
