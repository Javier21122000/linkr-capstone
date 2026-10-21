// Test di placeDetails.ts su risposte reali del geocoding MapTiler (fixtures/geocoding-features.json, ridotte ai campi usati).
// Eseguire con: npm test (Node ≥ 22.18 esegue direttamente i .ts "erasable").
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import {
  completePlace,
  distanceInMeters,
  needsReverse,
  parsePlaceFeature,
  parseReverseResponse,
  sanitizeHouseNumber,
} from "../src/lib/placeDetails.ts";

const features = JSON.parse(readFileSync(new URL("./fixtures/geocoding-features.json", import.meta.url), "utf8"));
const parse = (id) => {
  const place = parsePlaceFeature(features[id]);
  assert.ok(place, `${id} deve diventare un luogo`);
  return place;
};

test("civici: solo forme plausibili, le numerazioni interne non diventano civico", () => {
  for (const valid of ["39", "14a", "7/3", "12-14", "51b"]) assert.equal(sanitizeHouseNumber(valid), valid);
  for (const invalid of ["21n04", "2c01", "", "   ", "no", "S.N.C.", undefined, 39]) assert.equal(sanitizeHouseNumber(invalid), undefined);
});

test("POI con tag addr:*: via e civico del luogo stesso", () => {
  const place = parse("poi.14274203"); // Bar Basso
  assert.equal(place.venueName, "Bar Basso");
  assert.equal(place.address, "Via Plinio 39");
  assert.equal(place.addressQuality, "exact");
  assert.equal(place.neighbourhood, "Buenos Aires - Venezia");
  assert.equal(needsReverse(place), false);
});

test("POI senza tag: l'indirizzo si legge da place_name e il quartiere dal contesto", () => {
  const place = parse("poi.28873459"); // Triennale (bicycle_rental) con "Viale Emilio Alemagna 6" solo in place_name
  assert.equal(place.address, "Viale Emilio Alemagna 6");
  assert.equal(place.addressQuality, "exact");
  assert.equal(place.neighbourhood, "Parco Sempione");
});

test("POI con civico non plausibile: resta la via, mai un civico inventato", () => {
  const place = parse("poi.29134728"); // "Duomo, Piazza del Duomo 21n04, 20121 Milano"
  assert.equal(place.address, "Piazza del Duomo");
  assert.equal(place.addressQuality, "street");
});

test("POI senza alcun indirizzo: il testo opaco del contesto road.* non viene mai usato", () => {
  const place = parse("poi.39460316"); // Duomo di Milano: context road.304629 = "1_27487"
  assert.equal(place.address, undefined);
  assert.equal(place.neighbourhood, "Duomo");
  assert.equal(place.landmark, true);
  assert.equal(needsReverse(place), true);
  assert.ok(!JSON.stringify(place).includes("1_27487"));
});

test("castello: la via del contesto address.* è un indirizzo valido senza civico", () => {
  const place = parse("poi.12606228");
  assert.equal(place.address, "Viale Gerolamo Gadio");
  assert.equal(place.addressQuality, "street");
  assert.equal(place.landmark, true);
});

test("parco: il pezzo di place_name uguale al nome non è un indirizzo", () => {
  const place = parse("poi.12704719"); // "Parco Sempione, Parco Sempione, Milano, Italia"
  assert.equal(place.address, undefined);
  assert.equal(place.landmark, true);
  assert.equal(place.neighbourhood, "Parco Sempione");
});

test("quartiere cercato per nome: è il quartiere stesso e un luogo areale", () => {
  const place = parse("place.4281358"); // Navigli
  assert.equal(place.kind, "place");
  assert.equal(place.neighbourhood, "Navigli");
  assert.equal(place.landmark, true);
  assert.equal(place.address, undefined);
});

test("via cercata per nome: il luogo è la via", () => {
  const place = parse("address.8339952"); // Piazza del Duomo (senza civico nei risultati di ricerca)
  assert.equal(place.kind, "address");
  assert.equal(place.venueName, "Piazza del Duomo");
  assert.equal(place.address, "Piazza del Duomo");
});

test("risultati senza nome, id o coordinate sono scartati", () => {
  assert.equal(parsePlaceFeature(null), null);
  assert.equal(parsePlaceFeature({ id: "x", text: "", center: [9.19, 45.46] }), null);
  assert.equal(parsePlaceFeature({ id: "x", text: "Bar", center: [9.19] }), null);
  assert.equal(parsePlaceFeature({ text: "Bar", center: [9.19, 45.46] }), null);
});

const reverseFeature = (overrides) => ({
  features: [{
    id: "address.1",
    text: "Piazza del Duomo",
    address: "14a",
    place_type: ["address"],
    center: [9.19188, 45.46362],
    context: [
      { id: "place.4281341", text: "Duomo", kind: "place", place_designation: "quarter" },
      { id: "locality.18026", text: "Municipio 1" },
    ],
    ...overrides,
  }],
});

test("reverse: via, civico, quartiere e distanza dal punto", () => {
  const point = { latitude: 45.46417, longitude: 9.19161 }; // Duomo di Milano
  const reverse = parseReverseResponse(reverseFeature({}), point);
  assert.equal(reverse.street, "Piazza del Duomo");
  assert.equal(reverse.houseNumber, "14a");
  assert.equal(reverse.neighbourhood, "Duomo");
  assert.ok(reverse.distanceMeters > 40 && reverse.distanceMeters < 80, `distanza ${reverse.distanceMeters}`);
  assert.equal(parseReverseResponse({ features: [] }, point), null);
  assert.equal(parseReverseResponse("non json", point), null);
});

test("reverse: i municipi non sono quartieri", () => {
  const reverse = parseReverseResponse(reverseFeature({ context: [{ id: "locality.18026", text: "Municipio 1" }] }), { latitude: 45.4636, longitude: 9.1919 });
  assert.equal(reverse.neighbourhood, undefined);
});

test("monumento senza indirizzo: la via vicina è accettata (senza civico) e il quartiere si completa", () => {
  const place = parse("poi.39460316");
  const reverse = { street: "Piazza del Duomo", houseNumber: "14a", neighbourhood: "Duomo", distanceMeters: 70 };
  const completed = completePlace(place, reverse, "search");
  assert.equal(completed.address, "Piazza del Duomo");
  assert.equal(completed.addressQuality, "street");
  assert.equal(completed.neighbourhood, "Duomo");
});

test("monumento senza via vicina: indirizzo descrittivo con il nome, non una via lontana", () => {
  const completed = completePlace(parse("poi.39460316"), { street: "Via Lontana", distanceMeters: 400 }, "search");
  assert.equal(completed.address, "Duomo di Milano");
  assert.equal(completed.addressQuality, "descriptive");
});

test("locale ordinario senza indirizzo: via solo se davvero vicina, altrimenti resta da chiedere", () => {
  const bar = { venueName: "Bar Senza Via", landmark: false };
  const near = completePlace(bar, { street: "Via Vicina", houseNumber: "3", neighbourhood: "Isola", distanceMeters: 40 }, "search");
  assert.equal(near.address, "Via Vicina"); // mai il civico del palazzo accanto per un luogo cercato per nome
  assert.equal(near.neighbourhood, "Isola");
  const far = completePlace(bar, { street: "Via Lontana", neighbourhood: "Isola", distanceMeters: 200 }, "search");
  assert.equal(far.address, undefined);
  assert.equal(far.neighbourhood, "Isola");
  assert.equal(completePlace(bar, null, "search").address, undefined);
});

test("punto sulla mappa: il civico si usa solo se è a pochi metri, altrimenti la via", () => {
  const point = { venueName: "", landmark: false };
  assert.equal(completePlace(point, { street: "Via Magolfa", houseNumber: "9b", distanceMeters: 20 }, "map").address, "Via Magolfa 9b");
  assert.equal(completePlace(point, { street: "Via Magolfa", houseNumber: "9b", distanceMeters: 45 }, "map").address, "Via Magolfa");
  assert.equal(completePlace(point, { street: "Via Magolfa", houseNumber: "9b", distanceMeters: 90 }, "map").address, undefined);
});

test("completePlace non sovrascrive ciò che il luogo dichiara già", () => {
  const place = parse("poi.14274203"); // Bar Basso: già completo
  const completed = completePlace(place, { street: "Via Altra", houseNumber: "1", neighbourhood: "Altro", distanceMeters: 5 }, "search");
  assert.equal(completed.address, "Via Plinio 39");
  assert.equal(completed.neighbourhood, "Buenos Aires - Venezia");
});

test("distanza: Duomo - Castello Sforzesco ≈ 1,1 km", () => {
  const meters = distanceInMeters({ latitude: 45.46417, longitude: 9.19161 }, { latitude: 45.4703, longitude: 9.17809 });
  assert.ok(meters > 1000 && meters < 1300, `${meters}`);
});
