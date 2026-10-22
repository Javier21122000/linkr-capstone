# Integrazione Ticketmaster (Discovery API v2) — Sprint C2 (dry-run) e C3 (importazione)

**Stato:** due endpoint ADMIN, entrambi spenti di base. Il **dry-run** (`POST /api/admin/import/ticketmaster/dry-run`) legge gli eventi reali, li normalizza e dice
quanti e quali diventerebbero eventi LINKR: **non scrive nulla**. L'**importazione** (`POST /api/admin/import/ticketmaster`, sezione 10) crea e aggiorna gli eventi
in LINKR; ha un interruttore suo (`LINKR_TICKETMASTER_IMPORT_ENABLED`) e parte solo quando un admin la chiama.

Decisioni di prodotto in vigore: finestra di 30 giorni · massimo 200 candidati · area = riquadro di Milano del frontend · lingua italiana con fallback ·
segmenti non riconosciuti esclusi e contati · rinviati/ripianificati mai pubblicati in automatico · annullati mai riattivati in automatico ·
**nessuno scheduler** · nessuna vendita di biglietti · **nessuna immagine di Ticketmaster scaricata, copiata o pubblicata** · nessuna fusione automatica con eventi esistenti ·
l'importazione non modifica ne' cancella mai eventi community, demo o ufficiali creati a mano, ne' le partecipazioni.

## 1. Configurazione

Tutto si imposta con variabili d'ambiente (le stesse del resto del backend: `.env` caricato prima dell'avvio, vedi `.env.example`). Il file `.env` non va mai committato.

| Variabile | Default | Significato |
|---|---|---|
| `LINKR_TICKETMASTER_ENABLED` | `false` | **Interruttore generale.** Solo `true` abilita le richieste. |
| `LINKR_TICKETMASTER_IMPORT_ENABLED` | `false` | **Interruttore dell'importazione** (sezione 10). Il dry-run funziona anche con questo spento. |
| `LINKR_TICKETMASTER_API_KEY` | vuota | API Key della Discovery API. Solo backend. La Discovery API usa **solo** la API Key: nessun Consumer Secret. |
| `LINKR_TICKETMASTER_WINDOW_DAYS` | `30` | Finestra analizzata, da adesso (1–90). |
| `LINKR_TICKETMASTER_MAX_CANDIDATES` | `200` | Massimo di candidati validi raccolti (1–500). |
| `LINKR_TICKETMASTER_CONNECT_TIMEOUT` | `3s` | Timeout di connessione. |
| `LINKR_TICKETMASTER_READ_TIMEOUT` | `10s` | Timeout di lettura (per ogni lettura del socket). |

Parametri con un default prudente, modificabili solo da `application.properties` (prefisso `linkr.ticketmaster.`; elenco completo in
[`TicketmasterProperties`](../backend/src/main/java/com/linkr/backend/ticketmaster/TicketmasterProperties.java)):

| Proprieta' | Default | Significato |
|---|---|---|
| `page-size` | `50` | Eventi per pagina (1–200). |
| `max-pages` | `8` | Tetto assoluto di pagine per esecuzione. |
| `import-default-candidates` | `20` | Eventi importati se la richiesta non dice altro. |
| `import-max-candidates` | `50` | Massimo di eventi importabili con una sola richiesta (1–100). |
| `max-calls-per-run` | `15` | Tetto assoluto di richieste HTTP per esecuzione, retry compresi. |
| `max-retries` | `2` | Retry per errori temporanei (5xx, timeout, rete). Per un 429 al massimo **uno**. |
| `retry-backoff` | `500ms` | Attesa prima del primo retry, poi raddoppia (massimo 10 s). |
| `max-retry-after` | `5s` | Se Ticketmaster chiede di attendere di piu' (429 + `Retry-After`) non si attende: ci si ferma. |
| `min-call-interval` | `500ms` | Pausa minima tra due richieste: al massimo 2 al secondo (il limite della fonte e' ~5). |
| `locale` | `it-it,it,*` | Italiano con fallback sulle altre lingue disponibili. |
| `location-filter` | `GEOPOINT` | `GEOPOINT` (geohash, raccomandato dalla fonte) oppure `LATLONG` (deprecato dalla fonte, ma ancora accettato). |
| `area.*` | riquadro di Milano | `min/max-latitude`, `min/max-longitude` = `MILAN_BOUNDS` di `frontend/src/lib/map.ts` (45.35–45.60 N, 8.95–9.45 E). |
| `base-url` | `https://app.ticketmaster.com/discovery/v2` | Con integrazione attiva deve essere `https` (solo `localhost` ammesso in `http`, per i test). |

Con l'integrazione **attiva** valori fuori range fermano l'avvio con un messaggio chiaro (che non contiene mai la chiave). Con l'integrazione **spenta** non vengono controllati:
un parametro sbagliato non puo' impedire l'avvio dell'app.

La chiave non e' mai nel frontend, non e' mai nei log, nelle eccezioni o nelle risposte: viaggia nell'URL verso Ticketmaster (e' cosi' che la Discovery API la vuole), ma viene
aggiunta all'URL solo nell'ultimo istante prima dell'invio, e ogni errore viene riscritto con un testo fisso, senza URL ne' causa originale. Per prudenza non attivare
su un ambiente reale il log di dettaglio (`DEBUG`/`TRACE`/`FINE`) delle librerie HTTP di basso livello del JDK: potrebbero stampare la richiesta completa, chiave compresa (i log di Spring e dell'applicazione sono invece verificati da un test).

## 2. L'interruttore (kill switch)

| Situazione | Risposta di `POST /api/admin/import/ticketmaster/dry-run` | Richieste a Ticketmaster | Database |
|---|---|---|---|
| `ENABLED` non impostata o `false` | `200` con `status: "DISABLED"` e un messaggio | nessuna | non toccato |
| `ENABLED=true` ma senza API Key | `200` con `status: "NOT_CONFIGURED"` | nessuna | non toccato |
| Attiva e con chiave | esecuzione vera, `status` `OK` / `PARTIAL` / `FAILED` | si' | solo letture |
| Importazione con `IMPORT_ENABLED` non `true` | `200` con `status: "IMPORT_DISABLED"` (anche per `POST /api/admin/import/ticketmaster`) | nessuna | non toccato |

L'interruttore risponde per primo: anche con parametri sbagliati nella richiesta o nel tuning, da spento si ottiene sempre `DISABLED`.
All'**avvio** dell'applicazione non parte mai nessuna richiesta, e nel progetto non esiste nessuno scheduler (provato da test). Per spegnere tutto basta
rimettere `LINKR_TICKETMASTER_ENABLED=false` (o toglierla) e riavviare.

## 3. Eseguire il dry-run

**Chi puo':** solo un utente con ruolo `ADMIN`. Nessun endpoint assegna il ruolo: va impostato a mano nel database (operazione fuori da questo sprint) e poi ci si
autentica di nuovo, perche' il ruolo e' scritto nel token.

```bash
read -r -p "Email admin: " EMAIL; read -r -s -p "Password: " PASSWORD; echo      # nulla finisce nella cronologia della shell
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r .accessToken)

# Prima prova piccola: 7 giorni, al massimo 20 candidati
curl -s -X POST 'http://localhost:8080/api/admin/import/ticketmaster/dry-run?days=7&maxCandidates=20' \
     -H "Authorization: Bearer $TOKEN" | jq .

# Esecuzione completa (30 giorni, 200 candidati)
curl -s -X POST http://localhost:8080/api/admin/import/ticketmaster/dry-run -H "Authorization: Bearer $TOKEN" | jq .
```

I parametri `days` e `maxCandidates` sono facoltativi e possono solo **restringere** i limiti configurati (altrimenti `400`). Un solo dry-run alla volta (`409` se ne e' in corso uno).
La risposta e' sempre `200` con il report, a meno di `401`/`403` (non admin), `400` (parametri) o `409`: lo stato dell'esecuzione e' nel campo `status`.

## 4. Come leggere il report

| Campo | Cosa dice |
|---|---|
| `status` | `OK` completo · `PARTIAL` errore dopo almeno una pagina (o controllo duplicati fallito) · `FAILED` nessun dato · `DISABLED` / `NOT_CONFIGURED` non partito. |
| `dryRun` | Sempre `true`. |
| `window`, `limits` | Finestra effettiva (con fuso di Milano), riquadro e limiti usati. |
| `counts` | `received` (eventi ricevuti) = `validCandidates` + `excluded` + `skippedAfterLimit`. **I conti devono sempre tornare.** `skippedAfterLimit` = eventi gia' scaricati ma non esaminati perche' i 200 candidati erano pieni. |
| `excluded` | Totale e per gruppo: `coordinates` (con il dettaglio `coordinatesMissingOrInvalid` / `outsideArea`), `date`, `category`, `status`, `other`; `byReason` ha il motivo preciso. Ogni evento ha **un solo** motivo, il primo in questo ordine: test/dati essenziali → stato → data → coordinate → categoria. |
| `excludedBySegment` | I segmenti non riconosciuti con quanti eventi: dice quanto copertura si perde (es. `Miscellaneous`). |
| `categoryDistribution` | Candidati per categoria LINKR (tutte le categorie, anche a zero). |
| `potentialDuplicates` | Candidati **segnalati**, mai scartati: `alreadyImported` (id gia' in `event_source`), `withExistingEvents` (simili a un evento LINKR), `withinCandidates` (simili ad un altro candidato). `checked: false` = controllo non riuscito. |
| `quality` | Tra i candidati: quanti hanno descrizione, immagine (solo si'/no), sede, indirizzo, link all'annuncio, ora di fine; `titleLongerThanLimit` = titoli oltre i 120 caratteri di LINKR. |
| `api` | `calls` (richieste, retry compresi), `pagesFetched`, `retries`, `rateLimited`/`rateLimitedResponses`/`retryAfterSeconds`, `quotaLimit`/`quotaAvailable` (se la fonte li comunica), `totalAvailableAtSource` (quanti eventi esistono per la ricerca), `truncated` + `stoppedBy` (`SOURCE_EXHAUSTED`, `CANDIDATE_LIMIT`, `PAGE_LIMIT`, `ERROR`), `lastEventStartsAt` (fin dove arriva davvero la copertura temporale). |
| `errors` | Codice (`UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED`, `SERVER_ERROR`, `CLIENT_ERROR`, `TIMEOUT`, `NETWORK`, `MALFORMED_RESPONSE`, `CALL_BUDGET_EXHAUSTED`, `DUPLICATE_CHECK_FAILED`), esito HTTP e testo fisso. Mai URL, chiavi o testo della fonte. |
| `validSamples`, `duplicateSamples`, `excludedSamples` | Esempi ripuliti (testo senza caratteri di controllo, accorciato, chiavi mascherate). Non contengono **nessun URL**: ne' dell'annuncio, ne' delle immagini. |

**Come valutare la qualita':** guarda soprattutto `validCandidates` rispetto a `received` (copertura), `excludedBySegment` e `excluded.date` / `excluded.coordinates`
(dati inaffidabili), `api.truncated` (la finestra di 30 giorni e' coperta tutta? se `true`, `lastEventStartsAt` dice fino a quando), `quality` (quanti eventi avrebbero
descrizione e sede) e gli `excludedSamples` per capire se i motivi di esclusione sono giusti.

## 5. Regole di normalizzazione

- **Stato** (`dates.status.code`): `onsale` e `offsale` = candidato (l'evento si tiene; LINKR non vende biglietti). `canceled`, `postponed`, `rescheduled` e qualunque stato sconosciuto o assente = escluso.
- **Data:** serve data **e** ora. Esclusi `dateTBD`, `dateTBA`, `timeTBA`, `noSpecificTime`. Si usa l'istante UTC (`dateTime`); se manca, data e ora locali col fuso dell'evento, poi della sede, poi `Europe/Rome`.
  Se UTC e locale sono entrambi presenti e differiscono di piu' di 1 minuto l'evento e' escluso (`DATE_INCONSISTENT`). Deve cadere tra adesso e la fine della finestra. Nel report le date sono in `Europe/Rome`.
- **Coordinate:** dalla sede, solo decimali validi, fuori da `(0,0)`, e dentro il riquadro di Milano (la fonte non filtra per riquadro: si chiede un cerchio che lo contiene e si scarta fuori).
- **Prodotti:** abbonamenti, pacchetti, parcheggi, voucher, gift card, hospitality e simili non sono una singola uscita: esclusi (`NOT_SINGLE_EVENT`), riconosciuti dalle parole del titolo
  (italiano e inglese) e dal tipo/sottotipo della classificazione. E' un'euristica prudente: i casi dubbi si vedono in `excludedSamples` del dry-run e si correggono nelle liste di `TicketmasterEventNormalizer`.
- **Link all'annuncio:** conservato solo se e' un URL `https` valido, con dominio vero, senza credenziali, senza `apikey=`/`token=`, di al massimo 1000 caratteri; altrimenti e' assente (e l'importazione salta l'evento).
- **Ora locale ripetuta:** la notte del cambio ora legale (es. 02:30 del 25 ottobre 2026) l'ora locale esiste due volte; per UTC fa fede l'istante della fonte e il controllo di coerenza accetta entrambe le occorrenze.
- **Categoria** (segmento primario, per id e poi per nome in inglese/italiano): Music → `MUSIC` · Sports → `SPORT` · Arts & Theatre → `CULTURE` · Film → `CULTURE`.
  Tutto il resto (es. Miscellaneous, che mescola cibo, famiglia, fiere...) e' escluso e contato in `excludedBySegment`. Una mappatura piu' fine per genere (es. Dance/Electronic → `NIGHTLIFE`) e' rimandata.
- **Descrizione e immagini:** facoltative. Descrizione = `info`, altrimenti `pleaseNote`. Delle immagini si registra solo se esistono.
- **Duplicati:** inizio a meno di 3 ore, luogo a meno di 400 m, titoli simili (senza accenti/punteggiatura/parole generiche; sovrapposizione ≥ 60 %, oppure titolo piu' corto contenuto all'80 % con 2 parole in comune o una parola lunga). E' una segnalazione per una persona, non una fusione.

## 6. Limiti noti

- Il dry-run vede solo la **prima parte** della finestra se gli eventi sono piu' di quelli letti (ordine per data crescente). Il report lo dice (`api.truncated`, `lastEventStartsAt`).
- Il cerchio di ricerca che contiene il riquadro di Milano copre circa 1,7 volte la sua area: una parte degli eventi letti (e dei 200 candidati possibili) e' scartata come `OUTSIDE_AREA`.
- Il controllo dei duplicati e' euristico: puo' dare falsi positivi e falsi negativi. Per questo non scarta nulla.
- I segmenti sono riconosciuti per id e per nome (inglese/italiano). Gli id sono quelli noti della Discovery API ma **non sono stati verificati contro il servizio reale**: la prima esecuzione va letta con attenzione (`excludedBySegment`, `excludedSamples`).
- I parametri `locale=it-it,it,*` e `geoPoint` seguono la documentazione pubblica della Discovery API ma **non sono stati provati contro il servizio reale**: se Ticketmaster li rifiutasse si vedrebbe un `CLIENT_ERROR` (HTTP 400) nel report; si puo' tornare a `location-filter=LATLONG` o cambiare `locale` senza toccare il codice.
- Il limite di richieste e' in memoria e per esecuzione; la quota giornaliera della chiave (tipicamente 5000 chiamate) non e' tracciata dal backend: si vede in `api.quotaAvailable` se la fonte la comunica.
- Il ruolo ADMIN non ha ancora un modo guidato per essere assegnato.

## 7. Rischi legali e di attribuzione ancora aperti

Questa nota non e' una consulenza legale: sono i punti da **verificare sui termini attuali** del portale sviluppatori di Ticketmaster prima di importare o mostrare dati.

1. **Condizioni d'uso dell'API:** uso consentito per un progetto Capstone vs. uso commerciale; limiti sulla conservazione (cache/archivio) dei dati degli eventi e sul loro aggiornamento; obbligo di rimuovere o aggiornare eventi cancellati.
2. **Attribuzione e link:** probabile obbligo di indicare la fonte e di rimandare all'annuncio originale (per questo `event_source.source_url` esiste gia' in V9). Non e' ancora deciso come mostrarlo nell'interfaccia.
3. **Biglietti:** LINKR non li vende; se mostra un link di acquisto, deve portare a Ticketmaster senza alterarlo. Non e' ancora deciso se mostrarlo.
4. **Immagini:** escluse in questa fase. Prima di usarle serve capire licenza, hotlink vs. copia e attribuzione.
5. **Testi** (`info`, `pleaseNote`): sono contenuti di terzi; verificare se si possono riprodurre, tradurre o accorciare.
6. **Marchio:** uso del nome/logo Ticketmaster ("Powered by..."), se richiesto.
7. **Chiave API:** vanno rispettate le restrizioni della chiave (uso lato server). Se la chiave fosse mai finita in un log o in un commit va ruotata.
8. **Eventi gia' esistenti in LINKR:** nessuna fusione automatica; cosa fare con un evento Ticketmaster che coincide con uno della community e' una decisione di prodotto ancora aperta.
9. **Testo degli eventi importati:** la descrizione e' un testo neutro generato da LINKR e il testo della fonte non viene ripubblicato; se i termini lo consentissero si potrebbe usare. Gli eventi importati sono `OFFICIAL` in LINKR ma l'interfaccia li etichetta "Ticketmaster", non "Official".
10. **Aggiornamento dei dati importati:** non c'e' ancora una sincronizzazione periodica (nessuno scheduler) ne' la rimozione automatica di eventi spariti dalla fonte; gli annullamenti sulla fonte si registrano ma non si applicano da soli.

## 8. Database (V9)

La migrazione `V9__create_event_source.sql` e' **strettamente additiva**: crea solo la tabella `event_source` (collegamento evento ↔ annuncio esterno, relazione 1:1 con `event`: `UNIQUE (event_id)` e `UNIQUE (source, external_id)`),
senza toccare nessuna riga o colonna esistente. Il dry-run la legge (per riconoscere id gia' importati) ma non vi scrive; l'importazione (sezione 10) vi registra un collegamento per ogni evento importato (`source_url`, `source_status`, `last_seen_at`).
Flyway la applica da solo al primo avvio del backend contro il database a cui e' collegato; il test `FlywayV9UpgradeTest` la prova su dati popolati in uno schema usa-e-getta di `linkr_test_db`.

## 9. Test

Senza database (girano ovunque): normalizzatore, parser, client (stati HTTP, retry, timeout reali su localhost, JSON malformato, redazione della chiave nei messaggi e nei log),
servizio, autorizzazione ADMIN con i filtri di sicurezza veri, configurazione da `application.properties`, assenza di attivita' all'avvio e di scheduler.
Con `linkr_test_db` (servono `LINKR_DB_USERNAME`/`LINKR_DB_PASSWORD`, come in `docs/DEPLOY.md` §0): `TicketmasterDryRunIntegrationTest` (zero scritture: contenuti e contatori INSERT/UPDATE/DELETE
di ogni tabella identici prima e dopo; demo, community e partecipazioni invariati), `TicketmasterDisabledByDefaultIntegrationTest`, `EventSourceMigrationTest`, `FlywayV9UpgradeTest`.
I test non possono mai chiamare Ticketmaster: l'integrazione e' fissata a spenta in `@IntegrationTest` e i test che la accendono usano un client finto.

## 10. Importazione (sprint C3)

`POST /api/admin/import/ticketmaster?days=<1..30>&maxCandidates=<1..50>` — solo ADMIN, entrambi i parametri facoltativi e solo conservativi (di base 30 giorni e 20 eventi; fuori limite = `400`).
Serve `LINKR_TICKETMASTER_ENABLED=true`, la API Key e **`LINKR_TICKETMASTER_IMPORT_ENABLED=true`**; nessuno scheduler. Risponde sempre `200` con un report (campo `status`), `409` se ne e' in corso una.
Riusa client e normalizzatore del dry-run: **si scrive solo se la lettura da Ticketmaster e' completa** (un errore a meta' non scrive nulla).

**Cosa crea.** Per ogni candidato valido, in una transazione propria: un evento `OFFICIAL`, `PUBLISHED`, non demo, **senza creatore e senza immagine** (l'interfaccia mostra il segnaposto LINKR della categoria; nessuna immagine
di Ticketmaster viene usata), piu' il collegamento in `event_source` (`TICKETMASTER`, id dell'annuncio, link, stato visto, ultima volta visto). Titolo, sede e indirizzo sono accorciati ai limiti delle colonne; il "quartiere" e' la citta'
della sede (di base "Milano"). **La descrizione e' un testo neutro** ("Evento in programma presso <sede>. Per programma, prezzi e disponibilita' consulta la pagina ufficiale su Ticketmaster."): il testo della fonte non viene ripubblicato
(licenza non verificata) e non si inventa nulla. L'identita' "viene da Ticketmaster" e' `event_source`: `GET /api/events` e il dettaglio espongono `source: { provider, url }` (null per gli altri eventi).

**Idempotenza.** Rilanciare la stessa importazione non crea doppioni (controllo per id Ticketmaster e `UNIQUE (source, external_id)` / `UNIQUE (event_id)` nel database): gli eventi identici risultano `UNCHANGED`.

**Cosa aggiorna, e cosa mai.** Un evento gia' importato segue la fonte (titolo, orario, sede, indirizzo, coordinate, categoria) **solo se nessuno l'ha toccato**. Una persona che lo modifica o lo annulla dall'app lo rende "suo": l'importazione
non lo riscrive piu' (`KEPT_LOCAL_EDITS`, motivo `EDITED_IN_LINKR` o `CANCELLED_IN_LINKR`) e un evento annullato non viene **mai** riattivato. Si riconosce confrontando `event.updated_at` con `event_source.updated_at` (l'importazione scrive
sempre lo stesso valore in entrambi e non lo fa avanzare quando salta un evento). Stato, tipo, creatore, flag demo e immagini non vengono mai riscritti. Le UPDATE sono limitate a eventi collegati a Ticketmaster, `OFFICIAL`, non demo, senza creatore e
`PUBLISHED`, e solo se l'evento e' ancora identico a come lo si e' letto (nessuna sovrascrittura in caso di modifica simultanea). **Eventi community, demo e ufficiali creati a mano, partecipazioni e utenti non vengono mai modificati; nulla viene cancellato.**

**Cosa salta (e lo dice nel report).** Candidati senza link `https` valido o senza nome della sede (`SKIPPED_INCOMPLETE`); **duplicati evidenti** di un altro candidato o di un evento LINKR esistente, cioe' stesso titolo (normalizzato), stesso istante di inizio (entro
un minuto) e sede a meno di 100 m (`SKIPPED_DUPLICATE`). Due eventi diversi nella stessa sede, anche allo stesso orario o con titoli simili, restano entrambi. Per abbonamenti, pacchetti e prodotti vedi la sezione 5.

**Se Ticketmaster annulla o rinvia un evento gia' importato**, l'importazione registra solo lo stato in `event_source.source_status` (`SOURCE_STATUS_RECORDED`): l'evento LINKR non cambia e va annullato a mano da un admin.

**Report.** `status` (`OK`, `PARTIAL` se qualche evento non e' stato scritto, `FAILED`, `DISABLED`, `NOT_CONFIGURED`, `IMPORT_DISABLED`), `params`, `source` (gli stessi conteggi del dry-run), `result` (`created`, `updated`, `unchanged`,
`keptLocalEdits`, `skippedDuplicates`, `skippedIncomplete`, `failed`, `sourceStatusRecorded`) e `items` (per ogni evento: id Ticketmaster, titolo, azione, id dell'evento LINKR, motivo). Mai chiavi, URL o testo grezzo della fonte.

**Interfaccia.** Gli eventi importati compaiono su Discover con il badge discreto "Ticketmaster" (anche nell'anteprima sulla mappa); nella scheda evento il badge sostituisce "Official" e c'e' il pulsante **Dettagli ufficiali**, che apre
la pagina originale in una nuova scheda (`noopener noreferrer`, solo link `https` validati anche lato browser).

**Prima importazione reale, in sicurezza.**
1. Fai un dry-run piccolo (`?days=30&maxCandidates=10`) e leggi `validSamples` / `excludedSamples`: se compaiono abbonamenti o pacchetti non riconosciuti, vanno aggiunti alle liste del normalizzatore prima di importare.
2. Imposta `LINKR_TICKETMASTER_IMPORT_ENABLED=true` (in `.env`, da te) e riavvia il backend.
3. `POST /api/admin/import/ticketmaster?maxCandidates=10`, poi rileggi `result` e `items`; rilanciandola il risultato deve essere tutto `unchanged`.
4. Quando hai finito rimetti `LINKR_TICKETMASTER_IMPORT_ENABLED=false`.
