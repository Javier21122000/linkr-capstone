import assert from "node:assert/strict";
import { test } from "node:test";

import { resolveEventImageAlt } from "../src/lib/imageAlt.ts";

const values = {
  title: "Jazz al tramonto",
  description: "Musica dal vivo",
  category: "MUSIC",
  eventType: "COMMUNITY",
  startsAt: "2026-11-10T20:00",
  endsAt: "",
  venueName: "Parco Sempione",
  address: "Piazza Sempione",
  neighbourhood: "Centro",
  latitude: "45.472",
  longitude: "9.174",
  imageUrl: "",
  imageAlt: "",
  imageCredit: "",
};

test("l'ALT automatico usa titolo e luogo", () => {
  assert.equal(resolveEventImageAlt(values, "/api/event-images/cover.webp", "cover.webp"), "Copertina di Jazz al tramonto presso Parco Sempione");
});

test("un ALT personalizzato viene preservato", () => {
  assert.equal(resolveEventImageAlt({ ...values, imageAlt: "  Musicisti sul palco al tramonto  " }, "https://example.test/photo.jpg"), "Musicisti sul palco al tramonto");
});

test("il nome file ripulito è solo il fallback", () => {
  assert.equal(resolveEventImageAlt({ ...values, title: "", venueName: "" }, "https://example.test/foto_serata-milano.JPG?size=large"), "Immagine foto serata milano");
  assert.equal(resolveEventImageAlt({ ...values, title: "", venueName: "" }, "", "copertina_evento.png"), "");
});
