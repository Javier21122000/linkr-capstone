import assert from "node:assert/strict";
import { test } from "node:test";

import { cleanBio, cleanDisplayName, validateProfileDraft } from "../src/lib/profileValidation.ts";

test("il nome viene pulito come da contratto senza perdere il contenuto del form", () => {
  assert.equal(cleanDisplayName("  Anna   Maria  "), "Anna Maria");
  assert.deepEqual(validateProfileDraft("Anna Maria", "Ciao!"), {});
});

test("nome e bio rispettano i limiti e rifiutano markup o caratteri invisibili", () => {
  assert.ok(validateProfileDraft("A", "").displayName);
  assert.ok(validateProfileDraft("A".repeat(61), "").displayName);
  assert.ok(validateProfileDraft("Anna <Rossi>", "").displayName);
  assert.ok(validateProfileDraft("Anna", "A".repeat(301)).bio);
  assert.ok(validateProfileDraft("Anna", "ciao\u202emondo").bio);
});

test("la bio vuota diventa null e le righe vuote consecutive vengono limitate", () => {
  assert.equal(cleanBio("   \n "), null);
  assert.equal(cleanBio("Prima\n\n\n\nSeconda"), "Prima\n\nSeconda");
});
