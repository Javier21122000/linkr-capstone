# Condivisione e inviti agli eventi

Obiettivo: una persona crea un evento, lo manda a un amico (WhatsApp, Messaggi…), l'amico lo apre **senza account**, e se vuole partecipare accede o si registra e **torna all'evento**.

## Cosa c'era già (riusato)

- `/events/:eventId` è una rotta **pubblica** (fuori da `ProtectedRoute`) e `GET /api/events/{id}` non richiede login: un link diretto si apre anche da anonimi. Organizzatore con i soli dati pubblici (`userId`, `username`, `displayName`, `avatarUrl`; nessuna email).
- Il percorso anonimo → accesso → ritorno esisteva: "Parteciperò" da anonimo porta a `/login` con `state.from = location`; `LoginPage` e `RegisterPage` si passano lo stato a vicenda e `getSafeReturnPath` valida il ritorno. Lo stato sta nella cronologia del browser, quindi **sopravvive al refresh** di `/login` o `/register`.
- Partecipazione: `EventParticipationPanel` (dettaglio) e `EventPreview` (mappa) usano gli stessi endpoint `PUT/DELETE /api/events/{id}/participation`. Non è stato duplicato nulla.
- Non esistevano condivisione, `navigator.share` né copia del link.

## Cosa è stato aggiunto

| Pezzo | File |
|---|---|
| Indirizzo, testo, invio, ripiego | `lib/eventShare.ts` (puro, provato con `node --test`) |
| Hook e bottone "Condividi" | `hooks/useEventShare.ts`, `components/events/ShareEventButton.tsx` |
| Dettaglio evento | `pages/EventDetailPage.tsx`: "Condividi" accanto a "Visualizza sulla mappa", solo per eventi pubblicati e non finiti |
| Anteprima sulla mappa | icona nella riga "Chi viene?" (`EventPreviewPeople` + `.event-preview__share`), con avviso "Link copiato" |
| Ritorno dopo la registrazione | `RegisterPage`: se c'è un ritorno (`readReturnPath`) si va lì, altrimenti resta la scelta degli interessi |
| Anonimo sul dettaglio | `EventParticipationPanel`: "Accedi" e "Crea account" con il ritorno all'evento |
| Evento inesistente/annullato | `EventDetailPage`: stato esplicito e "Scopri altri eventi" (404 e 400 = "non esiste più, annullato o link errato") |

### Il link

`https://<origine pubblica>/events/<uuid>`, nient'altro: nessuna query, nessun dato di sessione o di chi condivide. L'origine è `VITE_PUBLIC_SITE_URL` se impostata, altrimenti quella da cui l'app è aperta. **Se non è pubblica** (http, `localhost`, indirizzi IP, `*.local`, `*.test`…) il pulsante non compare: un invito con `localhost` non si aprirebbe a nessuno. Per provarlo in locale: `VITE_PUBLIC_SITE_URL=https://linkr-web.onrender.com npm run dev` (l'indirizzo non viene mai contattato).

### Invio

1. `navigator.share({ title, text, url })` se esiste (iOS Safari, Android Chrome, app aggiunta alla Home). Chiamato **sincrono** nel gesto dell'utente, come richiede Safari.
2. Chiudere il foglio di sistema (`AbortError`) non mostra nulla e non copia nulla. Altri rifiuti ripiegano sulla copia.
3. Copia: `navigator.clipboard.writeText` → `document.execCommand("copy")` (iOS vecchi, contesti non sicuri) → se tutto fallisce, il link compare in un campo selezionabile (`role="alert"`).
4. "Link copiato" è annunciato ai lettori di schermo (`role="status"`, `aria-live="polite"`) e torna a "Condividi" dopo ~2,6 s.

### Scelte da conoscere

- **Registrazione da invito = niente scelta degli interessi.** Chi si registra partendo da una pagina che chiedeva l'accesso torna subito lì; gli interessi restano raggiungibili dal profilo. Senza ritorno (registrazione diretta) il flusso è quello di prima. Per mantenere l'onboarding anche dagli inviti basta passare `from` alla pagina `/profile/interests` e usare `getSafeReturnPath` al posto di `"/"` in `EditInterestsPage`.
- **Nessuna partecipazione automatica:** il ritorno non porta con sé nessuna azione; il visitatore trova di nuovo "Mi interessa" / "Parteciperò" e decide.
- **Open redirect:** il ritorno è sempre `state.from.pathname` del router (mai un parametro dell'URL) e passa da `readReturnPath`: scartati `//host`, `http://…`, `/\host`, `javascript:`, spazi e caratteri di controllo, `/login`, `/register`.
- **Anteprima sulla mappa:** l'icona è dentro la riga esistente (area di tocco 44 px, margini negativi): la scheda non cresce a 390 e 320 px. A 360 px il testo «Accedi per vedere chi viene» va a capo e la scheda è 7 px più alta, come già succede a 320 px.

## Anteprime WhatsApp: cosa succede oggi e proposta

**Oggi** (verificato con `GET` sul sito pubblico, anche con User-Agent `WhatsApp/2` e `facebookexternalhit`): `/events/<id>` risponde `200` con lo stesso `index.html` di ogni pagina (rewrite `/* → /index.html`, quindi il routing SPA funziona). La pagina non ha tag Open Graph: l'anteprima è quella generica («LINKR — Discover what’s happening» e la `description` dell'`index.html`), senza immagine né titolo dell'evento. I crawler non eseguono JavaScript, quindi il titolo che React imposta dopo non conta.

**Limiti da tenere presenti:** WhatsApp richiede `og:image` come URL **assoluto** e raster (JPEG/PNG/WebP, ideale 1200×630, < 600 KB); le copertine demo sono SVG (non mostrate) e le immagini caricate stanno sul dominio dell'API (`/api/uploads/event-images/…`). Le anteprime sono messe in cache da WhatsApp: dopo una modifica possono restare vecchie.

**Proposta, in ordine di costo (nulla di questo è implementato):**

1. *Anteprima di marca per tutti i link (solo statica, nessuna infrastruttura nuova).* Aggiungere a `index.html` `og:title`, `og:description`, `og:type`, `og:site_name`, `og:image` (PNG 1200×630 con URL assoluto, es. `https://linkr-web.onrender.com/og/linkr.png`) e `twitter:card`. Ogni link LINKR mostrerebbe la card di marca. Richiede un'immagine e l'origine fissa del sito.
2. *Anteprima per evento (consigliata).* Un endpoint HTML nel backend, `GET /share/events/{id}` (pubblico, sola lettura), che restituisce una pagina minima con `og:title` = titolo, `og:description` = «quando · dove» + inizio della descrizione, `og:image` = copertina raster in URL assoluto (o l'immagine di marca se manca/è SVG), `og:url` canonico e un reindirizzamento (`<meta http-equiv="refresh">` + `location.replace`) a `/events/{id}` sul sito. Eventi non pubblicati/annullati: pagina generica con 404. Il link da condividere diventa quello dell'endpoint. Due modi per esporlo sul dominio del sito, **entrambi richiedono una modifica a Render** (da autorizzare): una regola *Rewrite* `/share/*` → `https://<api>/share/*` sul sito statico, oppure condividere direttamente l'URL dell'API (funziona ma mostra il dominio dell'API). Costo: un controller e un test backend; nessuna nuova dipendenza.
3. *SSR o prerendering dell'intera SPA:* non necessario per questo obiettivo, più invasivo (nuovo servizio, build diversa).

Prima di (2) vale la pena produrre le copertine demo anche in PNG/WebP, altrimenti i link di eventi demo mostrano l'immagine di marca.

## Provare la condivisione su iPhone

1. Servono un sito con indirizzo pubblico (Render) o un build con `VITE_PUBLIC_SITE_URL` impostata; in locale, su `localhost`, il pulsante non c'è.
2. Safari → apri un evento (da Discover o da un link diretto) → **Condividi**: si apre il foglio di iOS. Scegli **WhatsApp** → il messaggio contiene titolo, quando/dove e il link; invia a un amico (o a «Tu»).
3. Chiudi il foglio senza scegliere: non deve comparire nessun messaggio.
4. Dall'amico (meglio senza account, scheda privata): tocca il link → si apre l'evento. **Parteciperò** → accesso; **Crea account** → registrazione; in entrambi i casi si torna sull'evento e le scelte sono di nuovo disponibili. Controlla che **non** risulti già «Parteciperò».
5. Ripeti dalla schermata Home (web app): stesso foglio di condivisione; l'accesso e il ritorno funzionano nella stessa finestra.
6. Da Discover, sulla mappa: tocca un evento → icona di condivisione nella riga «Chi viene?» → senza foglio di sistema compare «Link copiato».
7. Evento annullato o link errato (cambia una cifra dell'id): stato «Evento non disponibile» con «Scopri altri eventi».
