// Percorso dell'invito: l'evento si apre da un link diretto, senza account; la partecipazione chiede l'accesso e poi riporta lì.
// Verifiche sul codice (struttura delle rotte e dei passaggi di stato): il comportamento nel browser è provato a parte con un browser automatizzato.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const read = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");

test("la pagina dell'evento è pubblica: sta fuori da ProtectedRoute, quindi un link diretto si apre anche senza account", () => {
  const app = read("App.tsx");
  const eventRoute = app.indexOf('path="events/:eventId"');
  const protectedRoute = app.indexOf("<ProtectedRoute />");
  assert.ok(eventRoute > 0 && protectedRoute > 0);
  assert.ok(eventRoute < protectedRoute, "events/:eventId è dichiarata prima (e fuori) dal blocco protetto");
  assert.match(app.slice(eventRoute - 20, eventRoute + 80), /<EventDetailPage \/>/);
  // la rotta che modifica un evento, invece, resta protetta
  assert.ok(app.indexOf('path="events/:eventId/edit"') > protectedRoute);
});

test("anonimo: Partecipa (dettaglio e anteprima) porta all'accesso portando la posizione dell'evento", () => {
  for (const file of ["components/events/EventParticipationPanel.tsx", "components/events/EventPreview.tsx"]) {
    assert.match(read(file), /navigate\("\/login", \{ state: \{ from: location \} \}\)/, file);
  }
  assert.match(read("components/events/EventParticipationPanel.tsx"), /state=\{\{ from: location \}\}/, "anche Accedi e Crea account del dettaglio");
});

test("accesso e registrazione si passano il ritorno a vicenda e lo leggono con la stessa funzione validata", () => {
  const login = read("pages/LoginPage.tsx");
  const register = read("pages/RegisterPage.tsx");
  assert.match(login, /to="\/register" state=\{location\.state\}/);
  assert.match(register, /to="\/login" state=\{location\.state\}/);
  assert.match(login, /getSafeReturnPath\(location\.state\)/);
  assert.match(register, /readReturnPath\(location\.state\)/);
  assert.match(register, /getSafeReturnPath\(location\.state\)/);
});

test("registrazione: con un ritorno si va lì, altrimenti resta la scelta degli interessi", () => {
  const register = read("pages/RegisterPage.tsx");
  const after = register.slice(register.indexOf("registrationSucceededRef.current = true"));
  assert.match(after, /if \(returnPath\) navigate\(returnPath, \{ replace: true \}\)/);
  assert.match(after, /navigate\("\/profile\/interests", \{ replace: true, state: \{ onboarding: true \} \}\)/);
});

test("la partecipazione non parte da sola: il ritorno non porta nessuna azione, e l'unico punto che scrive è il clic dell'utente", () => {
  const register = read("pages/RegisterPage.tsx");
  const login = read("pages/LoginPage.tsx");
  for (const source of [register, login]) assert.doesNotMatch(source, /updateEventParticipation|participation/i);
  // un solo punto di scrittura, e il suo ramo anonimo esce verso l'accesso prima di ogni richiesta
  const panel = read("components/events/EventParticipationPanel.tsx");
  const save = panel.slice(panel.indexOf("async function saveParticipation"), panel.indexOf("async function removeParticipation"));
  assert.ok(save.indexOf('status === "anonymous"') < save.indexOf("updateEventParticipation"));
});

test("evento inesistente o annullato: stato esplicito con il motivo e una via d'uscita, non un 'Riprova' inutile", () => {
  const page = read("pages/EventDetailPage.tsx");
  assert.match(page, /isEventNotFound\(error\)/);
  assert.match(page, /non esiste più, è stato annullato oppure il link non è corretto/);
  assert.match(page, /Scopri altri eventi/);
  assert.match(page, /Evento non disponibile/);
});

test("Condividi compare solo per un evento pubblicato e non finito, accanto a 'Visualizza sulla mappa' e non dentro la bottom sheet", () => {
  const page = read("pages/EventDetailPage.tsx");
  assert.ok(page.includes("<ShareEventButton"));
  const guard = page.slice(page.lastIndexOf('eventState.event.status === "PUBLISHED"', page.indexOf("<ShareEventButton")), page.indexOf("<ShareEventButton"));
  assert.match(guard, /isEventEnded/);
  assert.doesNotMatch(read("components/events/EventList.tsx"), /Share/);
});
