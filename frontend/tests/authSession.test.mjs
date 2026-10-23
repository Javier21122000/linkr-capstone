// Sessione di accesso: persistenza tra una apertura e l'altra, scadenza, logout, migrazione dalla vecchia versione e storage non disponibile.
import assert from "node:assert/strict";
import { test } from "node:test";

import { MAX_TIMER_MS, SESSION_STORAGE_KEY, isExpired, loadSession, nextExpiryCheckDelay, parseStoredSession, saveSession } from "../src/lib/authSession.ts";

const NOW = Date.UTC(2026, 9, 24, 12, 0, 0);
const HOUR = 3_600_000;
const user = { id: "11111111-1111-4111-8111-111111111111", username: "qa", email: "qa@example.test", displayName: "QA", bio: null, avatarUrl: null, role: "USER", createdAt: "2026-01-01T00:00:00Z", discoverable: false };
const session = (expiresAt = NOW + 2 * HOUR) => ({ accessToken: "token-abc", expiresAt, user });

/** Uno storage finto: `items` è ciò che il browser conserva; chiuderlo e riaprirlo = nuova istanza sullo stesso `items`. */
function memoryStorage(items = new Map()) {
  return { items, getItem: (k) => items.get(k) ?? null, setItem: (k, v) => { items.set(k, String(v)); }, removeItem: (k) => { items.delete(k); } };
}
const blocked = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); }, removeItem() { throw new Error("blocked"); } };

test("chiudere e riaprire l'app: la sessione salvata nello storage persistente viene ripristinata", () => {
  const disk = new Map();
  saveSession(memoryStorage(disk), memoryStorage(), session());
  // nuova apertura: stessa memoria persistente, sessionStorage vuoto (la web app chiusa lo perde)
  const restored = loadSession(memoryStorage(disk), memoryStorage(), NOW + HOUR);
  assert.deepEqual(restored, session());
});

test("refresh: la sessione resta, e leggerla non la modifica", () => {
  const disk = new Map();
  saveSession(memoryStorage(disk), null, session());
  const before = disk.get(SESSION_STORAGE_KEY);
  assert.deepEqual(loadSession(memoryStorage(disk), null, NOW), session());
  assert.equal(disk.get(SESSION_STORAGE_KEY), before);
});

test("token scaduto: non si ripristina e la voce viene cancellata", () => {
  const disk = new Map();
  saveSession(memoryStorage(disk), null, session(NOW + HOUR));
  assert.equal(loadSession(memoryStorage(disk), null, NOW + HOUR), null, "scade esattamente alla scadenza");
  assert.equal(disk.size, 0);
  assert.equal(isExpired({ expiresAt: NOW }, NOW), true);
  assert.equal(isExpired({ expiresAt: NOW + 1 }, NOW), false);
});

test("logout: la voce sparisce da entrambi gli storage e la riapertura non ripristina nulla", () => {
  const disk = new Map(); const legacy = memoryStorage(new Map([[SESSION_STORAGE_KEY, JSON.stringify(session())]]));
  saveSession(memoryStorage(disk), legacy, session());
  saveSession(memoryStorage(disk), legacy, null);
  assert.equal(disk.size, 0);
  assert.equal(legacy.items.size, 0);
  assert.equal(loadSession(memoryStorage(disk), memoryStorage(), NOW), null);
});

test("contenuto non valido (JSON rotto, forma sbagliata, token vuoto, utente incompleto) non è una sessione e viene ripulito", () => {
  for (const raw of ["{", "null", "42", "[]", "{}", JSON.stringify({ ...session(), accessToken: "" }), JSON.stringify({ ...session(), expiresAt: "domani" }), JSON.stringify({ ...session(), user: { id: "x" } }), JSON.stringify({ ...session(), user: { ...user, role: "ROOT" } })]) {
    assert.equal(parseStoredSession(raw, NOW), null, raw);
    const disk = new Map([[SESSION_STORAGE_KEY, raw]]);
    assert.equal(loadSession(memoryStorage(disk), null, NOW), null);
    assert.equal(disk.size, 0, `ripulito: ${raw}`);
  }
  assert.equal(parseStoredSession(null, NOW), null);
});

test("aggiornamento dalla vecchia versione: chi aveva la sessione in sessionStorage non viene disconnesso, la sessione si sposta", () => {
  const legacy = memoryStorage(new Map([[SESSION_STORAGE_KEY, JSON.stringify(session())]]));
  const disk = new Map();
  assert.deepEqual(loadSession(memoryStorage(disk), legacy, NOW), session());
  assert.equal(legacy.items.size, 0, "la copia vecchia non resta");
  assert.ok(disk.has(SESSION_STORAGE_KEY), "e ora è persistente");
  // una sessione vecchia già scaduta non si sposta
  const expired = memoryStorage(new Map([[SESSION_STORAGE_KEY, JSON.stringify(session(NOW - 1))]]));
  const disk2 = new Map();
  assert.equal(loadSession(memoryStorage(disk2), expired, NOW), null);
  assert.equal(disk2.size, 0);
  assert.equal(expired.items.size, 0);
});

test("la sessione persistente vince su una copia vecchia, che viene tolta", () => {
  const disk = new Map([[SESSION_STORAGE_KEY, JSON.stringify(session())]]);
  const legacy = memoryStorage(new Map([[SESSION_STORAGE_KEY, JSON.stringify({ ...session(), accessToken: "vecchio" })]]));
  assert.equal(loadSession(memoryStorage(disk), legacy, NOW).accessToken, "token-abc");
  assert.equal(legacy.items.size, 0);
});

test("storage bloccato dal browser (navigazione privata, impostazioni): nessuna eccezione, sessione solo in memoria", () => {
  assert.equal(loadSession(blocked, blocked, NOW), null);
  assert.doesNotThrow(() => saveSession(blocked, blocked, session()));
  assert.doesNotThrow(() => saveSession(blocked, blocked, null));
  assert.equal(loadSession(null, null, NOW), null);
  assert.doesNotThrow(() => saveSession(null, null, session()));
});

test("scadenza: il timer non supera mai il limite di setTimeout e si riarma a tappe", () => {
  assert.equal(nextExpiryCheckDelay({ expiresAt: NOW + 2 * HOUR }, NOW), 2 * HOUR);
  assert.equal(nextExpiryCheckDelay({ expiresAt: NOW - 5 }, NOW), 0);
  const thirtyDays = 30 * 24 * HOUR;
  assert.equal(nextExpiryCheckDelay({ expiresAt: NOW + thirtyDays }, NOW), MAX_TIMER_MS, "30 giorni > 2^31-1 ms: senza limite scatterebbe subito");
  assert.ok(MAX_TIMER_MS > 24 * 24 * HOUR);
});

test("il token non finisce in nessuna chiave diversa da quella della sessione (niente copie sparse)", () => {
  const disk = new Map();
  saveSession(memoryStorage(disk), memoryStorage(), session());
  assert.deepEqual([...disk.keys()], [SESSION_STORAGE_KEY]);
});
