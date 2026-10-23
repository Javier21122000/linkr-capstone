// Token d'invito nel link: forma, lettura dall'indirizzo, stato del Link da mostrare e link condiviso con o senza token.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { buildEventShareUrl } from "../src/lib/eventShare.ts";
import { describeInviteLink, isInviteToken, readInviteToken } from "../src/lib/inviteToken.ts";

const EVENT = "018f0000-0000-7000-8000-000000000002";
const SITE = "https://linkr-web.onrender.com";
// stessa forma che emette il servizio: 41 byte -> 55 caratteri, ".", firma di 32 byte -> 43 caratteri
const TOKEN = `${"A".repeat(30)}${"b_-".repeat(8)}0.${"Z".repeat(40)}9_-`;
const read = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");

test("il token ha la forma del servizio e nient'altro", () => {
  assert.equal(TOKEN.split(".")[0].length, 55);
  assert.equal(TOKEN.split(".")[1].length, 43);
  assert.equal(isInviteToken(TOKEN), true);
  for (const bad of ["", "abc", `${TOKEN}.extra`, TOKEN.replace(".", ""), TOKEN.slice(1), `${TOKEN}A`, `${TOKEN} `, `<script>${TOKEN}`, TOKEN.replace("A", "+"), TOKEN.replace("A", "="), null, undefined, 42, {}]) {
    assert.equal(isInviteToken(bad), false, String(bad));
  }
});

test("dalla query si legge solo un token ben formato e non ambiguo", () => {
  assert.equal(readInviteToken(`?i=${TOKEN}`), TOKEN);
  assert.equal(readInviteToken(`?utm=1&i=${TOKEN}&x=2`), TOKEN);
  assert.equal(readInviteToken(""), null);
  assert.equal(readInviteToken("?x=1"), null);
  assert.equal(readInviteToken("?i="), null);
  assert.equal(readInviteToken("?i=garbage"), null);
  assert.equal(readInviteToken(`?i=${TOKEN}&i=${TOKEN}`), null, "ripetuto: ambiguo");
  assert.equal(readInviteToken(`?i=${encodeURIComponent("<img src=x>")}`), null);
  assert.equal(readInviteToken(`?inviter=${TOKEN}`), null, "solo il parametro `i`, nessun nome o id scelto nel link");
});

test("il link condiviso porta il token solo se ha la forma giusta; senza, è il link semplice di sempre", () => {
  assert.equal(buildEventShareUrl(SITE, EVENT), `${SITE}/events/${EVENT}`);
  assert.equal(buildEventShareUrl(SITE, EVENT, null), `${SITE}/events/${EVENT}`);
  assert.equal(buildEventShareUrl(SITE, EVENT, undefined), `${SITE}/events/${EVENT}`);
  assert.equal(buildEventShareUrl(SITE, EVENT, TOKEN), `${SITE}/events/${EVENT}?i=${TOKEN}`);
  for (const bad of ["garbage", "../x", `${TOKEN}&role=admin`, "<script>", "Mario Rossi"]) {
    assert.equal(buildEventShareUrl(SITE, EVENT, bad), `${SITE}/events/${EVENT}`, bad);
  }
  // un origine non pubblica non produce un invito nemmeno con un token
  assert.equal(buildEventShareUrl("http://localhost:5173", EVENT, TOKEN), null);
  // l'URL con il token si rilegge: stesso token, stesso evento
  const url = new URL(buildEventShareUrl(SITE, EVENT, TOKEN));
  assert.equal(readInviteToken(url.search), TOKEN);
  assert.equal(url.pathname, `/events/${EVENT}`);
});

test("lo stato del Link decide cosa proporre: azione esplicita solo se non c'è ancora nulla", () => {
  assert.equal(describeInviteLink("NONE"), "offer");
  assert.equal(describeInviteLink("OUTGOING_PENDING"), "sent");
  assert.equal(describeInviteLink("INCOMING_PENDING"), "received");
  assert.equal(describeInviteLink("ACCEPTED"), "connected");
  assert.equal(describeInviteLink("INCOMING_DECLINED"), "none", "un rifiuto non si ripropone");
  for (const unknown of [undefined, null, "", "ACCEPTEDX", 3]) assert.equal(describeInviteLink(unknown), "none");
});

test("da anonimo non si chiede e non si mostra nulla sul mittente; il token si prepara solo per chi è autenticato", () => {
  const hook = read("hooks/useEventInvite.ts");
  assert.match(hook, /if \(!token \|\| status !== "authenticated"\) return undefined;/);
  const share = read("hooks/useEventShare.ts");
  assert.match(share, /status === "authenticated" \? user\?\.id \?\? null : null/);
  assert.match(share, /if \(!cacheKey \|\| !baseUrl \|\| issuedTokens\.has\(cacheKey\)\) return undefined;/);
  assert.match(share, /`\$\{userId\}:\$\{event\.id\}`/, "la cache è per utente ed evento, mai condivisa tra utenti");
});

test("la richiesta Link parte solo dal gesto esplicito: un solo punto di invio, collegato al clic del pulsante", () => {
  const hook = read("hooks/useEventInvite.ts");
  assert.equal((hook.match(/sendInviteLinkRequest\(/g) ?? []).length, 1);
  const body = hook.slice(hook.indexOf("const connect"));
  assert.ok(body.indexOf("sendInviteLinkRequest(") > body.indexOf("const connect"));
  const card = read("components/events/InviteSharedBy.tsx");
  assert.match(card, /onClick=\{\(\) => void connect\(\)\}/);
  assert.equal((card.match(/connect\(\)/g) ?? []).length, 1);
  for (const file of ["pages/RegisterPage.tsx", "pages/LoginPage.tsx", "pages/EventDetailPage.tsx"]) assert.doesNotMatch(read(file), /sendInviteLinkRequest|connect\(/, file);
});

test("il messaggio dice chi ha condiviso, non che abbia invitato la persona; partecipazione e Link restano azioni separate", () => {
  const card = read("components/events/InviteSharedBy.tsx");
  const code = card.replace(/\/\*[\s\S]*?\*\//g, "");
  assert.match(code, /Evento condiviso da \{name\}/);
  assert.doesNotMatch(code, /invitat[oa]|ti ha invit|ti invita/i);
  assert.doesNotMatch(code, /participation|Parteciper|Ci sar/i, "la scheda non tocca la partecipazione");
  assert.doesNotMatch(read("components/events/EventParticipationPanel.tsx"), /invite|sendInviteLinkRequest/i, "e la partecipazione non tocca l'invito");
});

test("la scheda compare solo per eventi pubblicati; senza il token il link evento è quello di sempre", () => {
  const page = read("pages/EventDetailPage.tsx");
  assert.match(page, /eventState\.event\.status === "PUBLISHED" \? <InviteSharedBy eventId=\{eventState\.event\.id\} token=\{inviteToken\} \/> : null/);
  assert.match(page, /readInviteToken\(location\.search\)/);
});
