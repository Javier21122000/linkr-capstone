// Test della richiesta "Visualizza sulla mappa" e del ritorno dopo l'accesso.
import assert from "node:assert/strict";
import { test } from "node:test";

import { createBackState, createMapFocusState, getSafeReturnPath, readBackRequest, readMapFocusRequest } from "../src/lib/navigation.ts";

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
