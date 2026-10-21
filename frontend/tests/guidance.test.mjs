// Benvenuto e tour: persistenza, momento giusto per mostrarli e posizione del callout.
import assert from "node:assert/strict";
import { test } from "node:test";

import { isDiscoverReady, isOnboardingComplete, markOnboardingComplete, onboardingKey, placeTourCard, WELCOME_DELAY_MS } from "../src/lib/guidance.ts";

const memory = (initial = {}) => {
  const data = { ...initial };
  return { data, getItem: (key) => data[key] ?? null, setItem: (key, value) => { data[key] = value; } };
};

test("la chiave di persistenza è quella già in uso", () => {
  assert.equal(onboardingKey(null), "linkr:onboarding:v1:anonymous");
  assert.equal(onboardingKey("abc"), "linkr:onboarding:v1:user:abc");
});

test("prima visita: non completato; dopo «Non ora» non si ripresenta", () => {
  const storage = memory();
  assert.equal(isOnboardingComplete(storage, null), false);
  markOnboardingComplete(storage, null);
  assert.equal(isOnboardingComplete(storage, null), true);
});

test("chi lo ha chiuso da anonimo non lo rivede dopo l'accesso; un altro utente su un browser pulito sì", () => {
  const storage = memory();
  markOnboardingComplete(storage, null);
  assert.equal(isOnboardingComplete(storage, "u1"), true);
  const clean = memory();
  assert.equal(isOnboardingComplete(clean, "u1"), false);
  markOnboardingComplete(clean, "u1");
  assert.equal(isOnboardingComplete(clean, "u1"), true);
  assert.equal(isOnboardingComplete(clean, "u2"), false);
});

test("senza storage (o con storage che lancia) non si insiste con guide automatiche", () => {
  assert.equal(isOnboardingComplete(null, null), true);
  const broken = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); } };
  assert.equal(isOnboardingComplete(broken, null), true);
  assert.doesNotThrow(() => markOnboardingComplete(broken, null));
});

test("Discover è pronto solo quando è visibile, gli eventi sono arrivati e la mappa ha finito", () => {
  const ready = { active: true, eventsLoading: false, mapStatus: "ready" };
  assert.equal(isDiscoverReady(ready), true);
  assert.equal(isDiscoverReady({ ...ready, active: false }), false);
  assert.equal(isDiscoverReady({ ...ready, eventsLoading: true }), false);
  for (const mapStatus of ["loading", "recovering"]) assert.equal(isDiscoverReady({ ...ready, mapStatus }), false);
  for (const mapStatus of ["degraded", "error", "missing-key"]) assert.equal(isDiscoverReady({ ...ready, mapStatus }), true);
  assert.equal(WELCOME_DELAY_MS, 1000);
});

const desktop = { width: 1280, height: 800, insetTop: 0, insetBottom: 0 };
const mobile = { width: 390, height: 844, insetTop: 47, insetBottom: 34 };
const card = { width: 366, height: 200 };

test("elemento in alto (filtri): il callout sta sotto, con la freccia sul suo centro", () => {
  const target = { left: 400, top: 74, width: 700, height: 60 };
  const placement = placeTourCard(target, { width: 368, height: 190 }, desktop);
  assert.equal(placement.side, "bottom");
  assert.ok(placement.top >= target.top + target.height);
  const arrowX = placement.left + placement.arrow;
  assert.ok(Math.abs(arrowX - (target.left + target.width / 2)) <= 1);
});

test("elemento in basso (pulsante +): il callout sta sopra", () => {
  const target = { left: 1190, top: 720, width: 56, height: 56 };
  const placement = placeTourCard(target, { width: 368, height: 180 }, desktop);
  assert.equal(placement.side, "top");
  assert.ok(placement.top + 180 <= target.top);
  assert.ok(placement.left + 368 <= desktop.width - 12, "dentro la finestra a destra");
  assert.ok(placement.arrow >= 20 && placement.arrow <= 368 - 20);
});

test("mobile: callout a tutta larghezza dentro i margini, mai sotto la safe area", () => {
  const nav = { left: 12, top: 752, width: 366, height: 66 };
  const placement = placeTourCard(nav, card, mobile);
  assert.equal(placement.side, "top");
  assert.ok(placement.left >= 12 && placement.left + card.width <= mobile.width - 12);
  assert.ok(placement.top >= mobile.insetTop);
  const filters = { left: 12, top: 100, width: 366, height: 50 };
  const below = placeTourCard(filters, card, mobile);
  assert.equal(below.side, "bottom");
  assert.ok(below.top + card.height <= mobile.height - mobile.insetBottom);
});

test("elemento enorme (nessun lato libero): callout al centro in basso, senza freccia", () => {
  const map = { left: 0, top: 0, width: 1280, height: 800 };
  const placement = placeTourCard(map, card, desktop);
  assert.equal(placement.side, "center");
  assert.equal(placement.arrow, null);
  assert.ok(placement.top + card.height <= desktop.height);
});

test("senza elemento: centrato in basso, senza freccia", () => {
  const placement = placeTourCard(null, card, mobile);
  assert.equal(placement.side, "center");
  assert.equal(placement.arrow, null);
  assert.ok(placement.left >= 12 && placement.left + card.width <= mobile.width - 12);
});

test("elemento sul bordo destro: callout di lato se non c'è spazio sopra e sotto", () => {
  const tall = { left: 20, top: 20, width: 60, height: 760 };
  const placement = placeTourCard(tall, { width: 368, height: 200 }, desktop);
  assert.equal(placement.side, "right");
  assert.ok(placement.left >= tall.left + tall.width);
});

test("il callout non esce mai dalla finestra, qualunque sia la posizione dell'elemento", () => {
  for (let x = 0; x <= 1200; x += 100) {
    for (let y = 0; y <= 760; y += 80) {
      const placement = placeTourCard({ left: x, top: y, width: 56, height: 40 }, { width: 368, height: 190 }, desktop);
      assert.ok(placement.left >= 12 && placement.left + 368 <= 1280 - 12, `x=${x} y=${y} left=${placement.left}`);
      assert.ok(placement.top >= 12 && placement.top + 190 <= 800 - 12 + 1, `x=${x} y=${y} top=${placement.top}`);
    }
  }
});
