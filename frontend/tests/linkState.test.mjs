// Stato Link: come cambia dopo una risposta del servizio e che cosa offre il profilo pubblico in ogni stato.
import assert from "node:assert/strict";
import { test } from "node:test";

import { getProfileLinkAffordance, isStaleLinkStateTitle, linkStatusFromResponse, LINK_NEEDS_SHARED_INTEREST_HINT } from "../src/lib/linkState.ts";

const ME = "11111111-1111-4111-8111-111111111111";
const THEM = "22222222-2222-4222-8222-222222222222";
const REQUEST = "33333333-3333-4333-8333-333333333333";

function affordance(overrides = {}) {
  return getProfileLinkAffordance({ viewerId: ME, userId: THEM, linkStatus: "NONE", linkRequestId: null, sharedInterestsCount: 2, ...overrides });
}

test("la risposta del servizio dà lo stato Link giusto", () => {
  assert.equal(linkStatusFromResponse({ status: "PENDING", direction: "OUTGOING" }), "OUTGOING_PENDING");
  assert.equal(linkStatusFromResponse({ status: "PENDING", direction: "INCOMING" }), "INCOMING_PENDING");
  assert.equal(linkStatusFromResponse({ status: "ACCEPTED", direction: "INCOMING" }), "ACCEPTED");
  assert.equal(linkStatusFromResponse({ status: "ACCEPTED", direction: "OUTGOING" }), "ACCEPTED");
  assert.equal(linkStatusFromResponse({ status: "DECLINED", direction: "INCOMING" }), "INCOMING_DECLINED");
});

test("il mittente non vede mai un rifiuto: una richiesta inviata resta in attesa anche se risulta rifiutata", () => {
  assert.equal(linkStatusFromResponse({ status: "DECLINED", direction: "OUTGOING" }), "OUTGOING_PENDING");
});

test("nessuna relazione: Invia Link, ma solo con almeno un interesse in comune", () => {
  assert.deepEqual(affordance({ sharedInterestsCount: 1 }), { kind: "send" });
  assert.deepEqual(affordance({ sharedInterestsCount: 0 }), { kind: "needs-shared-interest" });
  assert.match(LINK_NEEDS_SHARED_INTEREST_HINT, /interesse in comune/);
});

test("richiesta inviata: solo lo stato, mai un secondo invio", () => {
  assert.deepEqual(affordance({ linkStatus: "OUTGOING_PENDING", linkRequestId: REQUEST }), { kind: "awaiting-answer" });
  // Anche senza interessi in comune (potrebbero essere cambiati dopo l'invio) non torna il pulsante.
  assert.deepEqual(affordance({ linkStatus: "OUTGOING_PENDING", linkRequestId: REQUEST, sharedInterestsCount: 0 }), { kind: "awaiting-answer" });
});

test("richiesta ricevuta: si accetta con l'identificativo della richiesta; senza, resta solo lo stato", () => {
  assert.deepEqual(affordance({ linkStatus: "INCOMING_PENDING", linkRequestId: REQUEST }), { kind: "can-accept", requestId: REQUEST });
  assert.deepEqual(affordance({ linkStatus: "INCOMING_PENDING", linkRequestId: null }), { kind: "received" });
});

test("richiesta archiviata: nessuna azione", () => {
  assert.deepEqual(affordance({ linkStatus: "INCOMING_DECLINED", linkRequestId: REQUEST }), { kind: "archived" });
});

test("collegati: Scrivi un messaggio, nella conversazione che ha lo stesso identificativo della richiesta", () => {
  assert.deepEqual(affordance({ linkStatus: "ACCEPTED", linkRequestId: REQUEST }), { kind: "can-message", conversationId: REQUEST });
  assert.deepEqual(affordance({ linkStatus: "ACCEPTED", linkRequestId: null }), { kind: "connected" });
});

test("il proprio profilo non mostra azioni Link, in qualunque stato", () => {
  for (const linkStatus of ["NONE", "OUTGOING_PENDING", "INCOMING_PENDING", "INCOMING_DECLINED", "ACCEPTED"]) {
    assert.deepEqual(affordance({ viewerId: THEM, linkStatus, linkRequestId: REQUEST }), { kind: "self" });
  }
});

test("il confronto col proprio identificativo ignora le maiuscole", () => {
  assert.deepEqual(affordance({ viewerId: THEM.toUpperCase() }), { kind: "self" });
  assert.deepEqual(affordance({ userId: THEM.toUpperCase(), viewerId: THEM }), { kind: "self" });
});

test("senza sapere chi guarda non si tratta il profilo come proprio", () => {
  assert.deepEqual(affordance({ viewerId: null }), { kind: "send" });
});

test("gli errori che dicono 'stato non più valido' sono solo quelli di richiesta già presente, già gestita, sparita o persona non raggiungibile", () => {
  for (const title of ["Link request already exists", "Link request already answered", "Link request not found", "Person not available"]) {
    assert.equal(isStaleLinkStateTitle(title), true, title);
  }
  for (const title of ["Discovery required", "Too many link requests", "Event has ended", "Invalid link request", "", undefined, null, 409]) {
    assert.equal(isStaleLinkStateTitle(title), false, String(title));
  }
});
