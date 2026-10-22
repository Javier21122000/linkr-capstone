// Dagli interessi dei profili ai filtri di Discover: categoria, indirizzo, lettura sicura dell'URL, ordine condivisi-prima.
import assert from "node:assert/strict";
import { test } from "node:test";

import { categoryForInterest, discoverPathForInterest, readInterestFilterRequest, splitInterests } from "../src/lib/interestFilter.ts";

const interest = (slug, categorySlug, name = slug) => ({ id: slug, slug, name, categorySlug });

test("gli interessi del catalogo vanno alla categoria evento pertinente", () => {
  assert.equal(categoryForInterest(interest("uk-garage", "music")), "MUSIC");
  assert.equal(categoryForInterest(interest("dj-set", "music")), "MUSIC");
  assert.equal(categoryForInterest(interest("basketball", "sport")), "SPORT");
  assert.equal(categoryForInterest(interest("calisthenics", "sport")), "SPORT");
  assert.equal(categoryForInterest(interest("street-food", "food")), "FOOD");
  assert.equal(categoryForInterest(interest("brunch", "food")), "FOOD");
  assert.equal(categoryForInterest(interest("photography", "art-culture")), "CULTURE");
  assert.equal(categoryForInterest(interest("clubbing", "nightlife")), "NIGHTLIFE");
  assert.equal(categoryForInterest(interest("hiking", "lifestyle")), "OUTDOORS");
  assert.equal(categoryForInterest(interest("languages", "lifestyle")), "SOCIAL");
});

test("un interesse senza categoria evento non ne inventa una", () => {
  for (const slug of ["travel", "technology", "fashion", "volunteering"]) {
    assert.equal(categoryForInterest(interest(slug, "lifestyle")), null);
  }
  assert.equal(categoryForInterest(interest("qualcosa", "categoria-nuova")), null);
});

test("l'indirizzo di Discover codifica categoria e nome, e si rilegge uguale", () => {
  const path = discoverPathForInterest(interest("uk-garage", "music", "UK Garage"));
  assert.equal(path, "/?categoria=MUSIC&interesse=UK+Garage");
  assert.deepEqual(readInterestFilterRequest(path.slice(1)), { category: "MUSIC", interestName: "UK Garage" });

  const noCategory = discoverPathForInterest(interest("fashion", "lifestyle", "Moda"));
  assert.equal(noCategory, "/?interesse=Moda");
  assert.deepEqual(readInterestFilterRequest(noCategory.slice(1)), { category: null, interestName: "Moda" });
});

test("categorie sconosciute, nome assente o enorme non diventano filtri", () => {
  assert.equal(readInterestFilterRequest(""), null);
  assert.equal(readInterestFilterRequest("?categoria=MUSIC"), null);
  assert.equal(readInterestFilterRequest("?categoria=HACK&interesse=x"), null);
  assert.equal(readInterestFilterRequest("?categoria=music&interesse=x"), null);
  assert.equal(readInterestFilterRequest("?interesse=%20%20"), null);
  assert.equal(readInterestFilterRequest(`?interesse=${"a".repeat(500)}`)?.interestName.length, 40);
});

test("gli interessi condivisi vengono prima, senza perdere né duplicare nessuno", () => {
  const all = ["techno", "house", "jazz", "gym", "brunch"].map((slug) => interest(slug, "x"));
  const { shared, others } = splitInterests(all, [{ id: "jazz" }, { id: "techno" }, { id: "non-suo" }]);
  assert.deepEqual(shared.map((i) => i.id), ["techno", "jazz"]);
  assert.deepEqual(others.map((i) => i.id), ["house", "gym", "brunch"]);
  assert.equal(shared.length + others.length, all.length);
});
