# Sessioni più lunghe e inviti sociali

**Stato:** la parte B (inviti sociali) è **implementata** nella versione stateless approvata, con le condizioni descritte in [`CONDIVISIONE-EVENTI.md`](CONDIVISIONE-EVENTI.md#inviti-sociali-evento-condiviso-da-nome). La parte A (sessioni più lunghe) resta una proposta: il frontend ha solo la persistenza fino alla scadenza del token (`lib/authSession.ts`).

## A. Sessione che dura più di 2 ore

**Stato attuale.** Il backend emette un solo JWT di accesso (`LINKR_JWT_EXPIRATION`, default `2h`, nessun refresh token). Prima il token stava in `sessionStorage`: chiudere la scheda o la web app dalla Home lo cancellava subito. Ora sta in `localStorage`: la sessione sopravvive a chiusura, riapertura e refresh **fino alla scadenza del token**, poi serve un nuovo accesso. L'accesso fatto in Safari e quello nella web app dalla Home non si condividono (storage separati su iOS).

Opzioni per andare oltre le 2 ore:

| | A1 — solo configurazione | A2 — refresh token opaco (consigliata) |
|---|---|---|
| Cosa | Alzare `LINKR_JWT_EXPIRATION` (es. `24h` o `7d`) su Render | `POST /api/auth/refresh` e `POST /api/auth/logout`; access JWT breve (15–30 min); refresh token casuale, salvato **hashato**, con rotazione a ogni uso e rilevamento del riuso (si revoca tutta la famiglia) |
| Codice | Nessuno (il timer del frontend regge già scadenze lunghe) | Migrazione `V10` (tabella `refresh_token`), servizio e controller, test; frontend: rinnovo silenzioso a richiesta singola (single-flight), riconnessione del WebSocket col nuovo token |
| Sicurezza | Un token rubato vale per tutta la durata e non si revoca (solo cambiando `LINKR_JWT_SECRET`, che disconnette tutti) | Token rubato = finestra breve; revocabile; il logout lo invalida davvero |
| Costo | Minuto; richiede una modifica a Render | Alto (circa una giornata con test) |

Nota sul cookie `HttpOnly`: con frontend e API su due sottodomini di `onrender.com` il cookie sarebbe di terze parti e Safari lo blocca (ITP). Il refresh token starebbe quindi in `localStorage` (esposto a XSS, ma ruotato e revocabile). Con un dominio proprio per sito e API (es. `app.` e `api.`) si potrebbe usare un cookie `HttpOnly; Secure; SameSite=Lax`, molto più sicuro.

**Per la presentazione:** A1 con `24h` è sufficiente e non cambia il codice. A2 dopo la demo, idealmente con dominio proprio.

## B. Inviti sociali («Evento condiviso da [nome]»), versione proposta originale — vedi lo stato sopra per ciò che è stato realizzato

**Perché servono nuovi endpoint.** Con gli endpoint attuali non si può né mostrare né verificare chi ha condiviso:

- `GET /api/users/{id}/profile` richiede login e vale solo con reciprocità (entrambi visibili in Discover People), partecipazione visibile a un evento in comune, oppure Link accettato (`ProfileRepository.findVisible`). Un anonimo non vede nessun profilo; un nuovo iscritto (non visibile di default) neppure.
- `POST /api/link-requests` richiede, con `eventId`, che il mittente partecipi e che il destinatario sia partecipante visibile; senza `eventId`, che entrambi siano visibili e abbiano un interesse in comune. Un invitato appena iscritto di norma non le soddisfa.
- Un parametro non firmato (`?inviter=<id>` o `?da=Javier`) si può falsificare: chiunque potrebbe far comparire «Javier ti ha invitato» con un link a suo piacimento. Escluso.

**Proposta minima (stateless, senza tabelle né migrazioni):**

1. **Token di invito firmato.** `v1.<base64url(eventId|senderId)>.<HMAC-SHA256>` con chiave derivata dal segreto JWT esistente (nessuna nuova configurazione). Deterministico: lo stesso mittente genera sempre lo stesso link per lo stesso evento. Link: `https://…/events/<id>?i=<token>`.
2. `POST /api/events/{id}/invites` (autenticato, evento pubblicato): restituisce il token. Il frontend lo richiede **in anticipo** (alla comparsa del pulsante) per non rompere il gesto dell'utente richiesto da `navigator.share` su iOS; se non è ancora pronto si condivide il link semplice.
3. `GET /api/invites/{token}` (pubblico, accesso facoltativo): verifica firma, evento pubblicato e non finito, mittente esistente. Risponde con `eventId` e `sender { displayName, avatarUrl }`; con login anche lo stato Link col mittente (`NONE`, `OUTGOING_PENDING`, `INCOMING_PENDING`, `ACCEPTED`). Ogni altro caso: 404 uniforme. Mai email, bio, interessi, username.
4. `POST /api/invites/{token}/link-request` (autenticato): crea una richiesta Link **PENDING** verso il mittente. L'invito vale come consenso preventivo del mittente a ricevere la richiesta (bypassa visibilità/partecipazione), ma restano valide le regole anti-spam, «una richiesta per coppia» e la privacy del rifiuto; il mittente deve comunque accettare. Se esiste già una richiesta o un Link, si restituisce lo stato esistente (409 → la UI lo mostra).
5. **Mittente anonimo:** il pulsante Condividi non aggiunge nessun token; nessun banner, nessuna identità inventata.

**Frontend (dopo l'approvazione):** banner discreto e chiudibile sul dettaglio («Javier ti ha invitato a questo evento»), nascosto se il visitatore è il mittente; dopo accesso o registrazione il parametro `i` viaggia già nella posizione di ritorno (`state.from.search`); una scheda separata «Connettiti con Javier» con pulsante esplicito (mai automatico), distinta da «Parteciperò». Nessun blocco della visualizzazione né della registrazione.

**Compromessi da decidere:**

- *Consenso:* chi condivide mostra il proprio nome visualizzato e l'avatar a chiunque apra il link (anche anonimo) e può ricevere richieste Link dall'invitato. Serve una riga chiara nell'interfaccia («Il tuo nome comparirà a chi apre il link»). In alternativa, mostrare il mittente solo a chi è già autenticato.
- *Inoltro:* se l'amico inoltra il link, chi lo apre vede ancora «X ti ha invitato». Accettabile (è un invito di X), limitato dalle regole anti-spam.
- *Revoca:* un token deterministico non si revoca singolarmente (solo cambiando il segreto, che disconnette tutti). Alternativa: tabella `event_invite` (migrazione `V10`) con token casuale: revocabile, con scadenza e conteggi, più codice. Si può passare dall'una all'altra senza cambiare il frontend.
- *Username:* mostrare solo il nome visualizzato (proposta) o anche `@username`.

**Stima:** backend ~250 righe + test (firma alterata, evento finito/annullato, privacy, 404 uniforme, rate limit, Link duplicato/già collegato); frontend ~200 righe + test. Nessuna nuova dipendenza.

## Decisioni richieste

1. A: A1 (configurazione, `24h`/`7d`) ora, A2 in seguito? Oppure A2 subito?
2. B: approvare gli endpoint `GET /api/invites/{token}`, `POST /api/events/{id}/invites`, `POST /api/invites/{token}/link-request` e la regola «l'invito vale come consenso».
3. B: token stateless (consigliato per l'MVP) o tabella con revoca.
4. B: nome visualizzato visibile anche agli anonimi, o solo dopo l'accesso.
