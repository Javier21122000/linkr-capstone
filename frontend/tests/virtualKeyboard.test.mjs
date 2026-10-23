// Tastiera virtuale e campo attivo: altezza coperta, pannello compatto, scorrimento perché il campo resti visibile, campi di testo.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { COMPACT_VISIBLE_PX, KEYBOARD_MIN_PX, isTextEntryField, keyboardHeight, revealDelta, shouldUseCompactPanel } from "../src/lib/virtualKeyboard.ts";

const read = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");
const geo = (over = {}) => ({ layoutHeight: 664, visualHeight: 664, visualOffsetTop: 0, scale: 1, ...over });

test("altezza della tastiera: layout meno viewport visivo (e il suo scorrimento), solo se è davvero una tastiera", () => {
  assert.equal(keyboardHeight(geo()), 0);
  assert.equal(keyboardHeight(geo({ visualHeight: 373 })), 291);
  assert.equal(keyboardHeight(geo({ visualHeight: 373, visualOffsetTop: 40 })), 251, "il viewport visivo scorso verso il basso copre meno");
  assert.equal(keyboardHeight(geo({ visualHeight: 664 - KEYBOARD_MIN_PX })), 0, "sotto la soglia è la barra del browser che si ritrae");
  assert.equal(keyboardHeight(geo({ visualHeight: 300, scale: 2 })), 0, "lo zoom a pinch non è una tastiera");
  assert.equal(keyboardHeight(geo({ visualHeight: 800 })), 0);
});

test("pannello compatto: poco spazio visibile, oppure intestazione e CTA che ne occupano troppo (testo ingrandito, Display Zoom)", () => {
  assert.equal(shouldUseCompactPanel(664, 168), false, "iPhone 13, tastiera chiusa: layout normale");
  assert.equal(shouldUseCompactPanel(520, 168), false, "Display Zoom, tastiera chiusa");
  assert.equal(shouldUseCompactPanel(262, 168), true, "Display Zoom con tastiera: il corpo avrebbe 63 px");
  assert.equal(shouldUseCompactPanel(373, 168), true, "iPhone 13 con tastiera");
  assert.equal(shouldUseCompactPanel(COMPACT_VISIBLE_PX - 1, 100), true);
  assert.equal(shouldUseCompactPanel(COMPACT_VISIBLE_PX, 100), false);
  assert.equal(shouldUseCompactPanel(520, 252), true, "testo al 150%: intestazione + CTA = 48% dello spazio");
  assert.equal(shouldUseCompactPanel(664, 252), false);
});

test("scorrimento verso il campo: nessuno se è già visibile, il minimo necessario altrimenti", () => {
  const band = { boundTop: 100, boundBottom: 400 };
  assert.equal(revealDelta({ ...band, fieldTop: 200, fieldBottom: 250 }), 0);
  assert.equal(revealDelta({ ...band, fieldTop: 112, fieldBottom: 160 }), 0, "esattamente sul margine di 12 px");
  assert.equal(revealDelta({ ...band, fieldTop: 110, fieldBottom: 160 }), -2, "due pixel dentro il margine: uno scatto minimo");
  assert.equal(revealDelta({ ...band, fieldTop: 380, fieldBottom: 440 }), 440 - (400 - 12), "sotto la tastiera: si scorre verso il basso, fino al margine");
  assert.equal(revealDelta({ ...band, fieldTop: 40, fieldBottom: 90 }), 40 - (100 + 12), "sotto l'header: si scorre verso l'alto");
  assert.equal(revealDelta({ ...band, fieldTop: 150, fieldBottom: 600 }), 150 - 112, "un campo più alto della fascia (textarea) si allinea in alto");
  assert.equal(revealDelta({ boundTop: 100, boundBottom: 110, fieldTop: 500, fieldBottom: 550 }), 0, "fascia inesistente: non si fa nulla");
});

test("campi in cui si scrive: quelli che aprono la tastiera, non checkbox, file o pulsanti", () => {
  for (const [tag, type] of [["input", "text"], ["input", null], ["input", "email"], ["input", "password"], ["input", "number"], ["input", "datetime-local"], ["INPUT", "url"], ["textarea", null], ["select", null]]) {
    assert.equal(isTextEntryField(tag, type), true, `${tag} ${type}`);
  }
  for (const [tag, type] of [["input", "checkbox"], ["input", "radio"], ["input", "file"], ["input", "submit"], ["input", "range"], ["button", null], ["div", null], ["a", null]]) {
    assert.equal(isTextEntryField(tag, type), false, `${tag} ${type}`);
  }
  assert.equal(isTextEntryField("div", null, true), true);
});

test("il modulo e il dialogo usano il supporto tastiera; la CTA smette di galleggiare e il documento si allunga quando la tastiera è aperta", () => {
  assert.match(read("pages/CreateEventPage.tsx"), /useFormKeyboardSupport\(pageRef, !loading && !loadError\)/);
  assert.match(read("pages/CreateEventPage.tsx"), /className="page-shell page-with-mobile-nav event-form-page /);
  const css = read("styles/pages.css").replace(/\/\*[\s\S]*?\*\//g, "");
  assert.match(css, /\.event-form-page\.page-with-mobile-nav\[data-keyboard="open"\]\s*\{\s*padding-bottom:\s*calc\(var\(--kb-inset, 0px\) \+ 2rem\)/);
  assert.match(css, /\.event-form-page\[data-keyboard="open"\] \.create-event-actions\s*\{\s*position:\s*static/);
  const dialog = read("components/events/QuickCreateDialog.tsx");
  assert.match(dialog, /shouldUseCompactPanel\(viewport\.height, chrome\)/);
  assert.match(dialog, /panel\.addEventListener\("focusin", onFocusIn\)/);
  const qc = read("styles/quick-create.css").replace(/\/\*[\s\S]*?\*\//g, "");
  assert.match(qc, /\.qc-dialog\[data-compact="true"\]\s*\{\s*overflow-y:\s*auto/);
  assert.match(qc, /\.qc-dialog\[data-compact="true"\] \.qc-dialog__footer\s*\{[^}]*position:\s*sticky[^}]*bottom:\s*0/);
});

test("nessun ascoltatore globale: solo visualViewport e l'elemento stesso", () => {
  for (const file of ["hooks/useKeyboardInsets.ts", "components/events/QuickCreateDialog.tsx"]) {
    const code = read(file).replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
    assert.doesNotMatch(code, /document\.addEventListener|window\.addEventListener/, file);
  }
});

test("fotocamera: l'input «Carica foto» non impone nessuna fotocamera, quello di «Scatta foto» usa capture senza valore; mai capture=\"environment\"", () => {
  const field = read("components/events/EventCoverField.tsx").replace(/\/\*[\s\S]*?\*\//g, "");
  assert.doesNotMatch(field, /capture="environment"|capture=\{"environment"\}|capture="user"/);
  assert.equal((field.match(/\bcapture\b(?!\w)/g) ?? []).length >= 1, true);
  assert.match(field, /ref=\{fileInputRef\}\s+type="file"\s+accept=\{EVENT_IMAGE_ACCEPT\}\s+onChange=\{handleFileChange\}/);
  assert.match(field, /ref=\{cameraInputRef\}\s+type="file"\s+accept=\{EVENT_IMAGE_ACCEPT\}\s+capture\s/);
  assert.match(field, /\(pointer: coarse\)/);
  assert.match(field, /nativeCamera \? cameraInputRef\.current\?\.click\(\) : setCameraOpen\(true\)/);
});
