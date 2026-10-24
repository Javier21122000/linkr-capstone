# Mappa, luoghi, orari e navigazione

Note di progetto sul rinnovo UX di ottobre 2026: cosa è stato deciso, dove vive e come si verifica.

## Cartografia essenziale (`frontend/src/lib/mapStyle.ts`)

- Lo stile MapTiler `streets-v4` resta quello **remoto e condiviso, mai modificato**. Il client lo riceve (una sola richiesta, la stessa di prima) e lo
  alleggerisce con `transformStyle` prima di usarlo. Reversibile: `VITE_MAP_STYLE=full` nel `.env` restituisce lo stile completo.
- Tolti: layer 3D (`fill-extrusion`), negozi, ristoranti, alberghi, sanità, scuole, sport, benzinai e parcheggi (sono tutti in "Transport"), arredo stradale,
  alberi, numeri civici, etichette e confini di scala nazionale/continentale (con zoom ≥ 9 su Milano sono solo rumore).
- Restano: strade, parchi, acque, quartieri, stazioni di treno e metropolitana, musei/teatri/monumenti/castelli, fermate dei bus (da zoom 16).
- Gli edifici restano come impronta piatta anche oltre zoom 15 (prima li disegnava il layer 3D). Le strade principali sono meno sature, perché non
  competano con i marker corallo.
- Mappa solo 2D: `maxPitch: 0` (nemmeno il gesto a due dita inclina), `pixelRatio` limitato a 2.
- Un errore di tassello, glifo o sprite non impedisce l'avvio: la mappa mostra «non disponibile» solo se lo stile non si scarica. Che cosa succede dopo (recupero, messaggio, **Riprova**) lo decide il controllore di salute descritto qui sotto.

Misure (Chrome con WebGL software, quindi solo relative):

| | prima | dopo |
|---|---|---|
| layer dello stile | 164 | 106 |
| layer `symbol` (etichette/icone) | 59 | 22 |
| layer 3D | 1 | 0 |
| richieste MapTiler al primo caricamento | 21 | 20 |
| byte MapTiler (stile, sprite, glifi, tasselli) | 1442 kB | 1364 kB |
| tempo medio per frame durante un pan | 78 ms | 58 ms (67 ms con il pannello in vetro) |

## Stabilità della mappa (`lib/mapHealth.ts`, `lib/mapRecovery.ts`, `EventMap.tsx`)

Sintomo: a volte Discover mostrava marker, lista e controlli MapLibre ma uno sfondo uniforme, senza strade né edifici.

**Fatti verificati nel browser** (guasti iniettati con route di Playwright: rete interrotta, 403, 429, 503, risposte lente, perdita del contesto WebGL):

- Quando i tasselli vettoriali falliscono MapLibre emette `error`, ma poi dichiara comunque `load` e `idle`: la mappa risulta «pronta» pur senza cartografia.
- I tasselli falliti non vengono ritentati da soli, né da `refreshTiles`, `setTiles` o `setUrl`. `setStyle(url, { diff: false })` sulla stessa istanza li ricarica.
- Il test oggettivo di una mappa vuota è `queryRenderedFeatures().length === 0` con il contenitore visibile.
- Prima di questo lavoro un errore di tassello non portava a nessuna fase di errore o di recupero: la mappa restava vuota e senza "Riprova". Il pulsante, quando c'era, ricreava la mappa senza reinserire i marker.

**Ipotesi non provate**: l'innesco naturale. In 12 caricamenti naturali la mappa non si è mai svuotata. Le cause plausibili (un 429 o un errore di rete sui tasselli all'avvio, la perdita del contesto WebGL) producono tutte il sintomo osservato, ma non si è stabilito quale sia quella reale. Non si presume che la colpa sia del servizio cartografico.

Comportamento attuale (`data-status` su `.event-map`: `loading`, `ready`, `recovering`, `degraded`, `error`, `missing-key`):

- A ogni `load`/`idle` il controllore verifica che la mappa disegni qualcosa e che nessun errore di cartografia sia rimasto in sospeso. «Pronta» significa questo, non solo `load`.
- Al massimo **due tentativi automatici** (dopo 1,5 s e 4,5 s; con 429 dopo 5 s e 12 s). Mai un ciclo infinito. Gli `idle` durante l'attesa non accumulano tentativi.
- Un errore permanente (400, 401, 403) non si ritenta: la fase è subito `degraded` e il messaggio riporta il codice. Non si maschera una chiave rifiutata.
- Il recupero ricarica stile e tasselli **sulla stessa istanza**: camera, marker, filtri e selezione non si toccano. Un tassello inesistente (404/204) non è un guasto.
- Dopo i tentativi la mappa resta utilizzabile, con un avviso e **Riprova**. «Riprova» ricrea la mappa solo se non è mai partita o se il browser ha perso il contesto WebGL; in quel caso conserva l'ultima camera (`moveend`) e reinserisce i marker.
- Perdita del contesto WebGL: fase `recovering`; se non torna entro 5 s, `degraded`.
- Se la mappa è lenta (oltre 20 s) si continua ad aspettare, ma compare «Riprova».
- Nessuna richiesta a MapTiler fuori da Discover: il `preconnect` non è più in `index.html` ma lo aggiunge `main.tsx` solo quando la pagina aperta è `/`. Lo stesso controllore serve `EventLocationPicker`.

### Canvas rimasto di 400×300 dopo il ritorno su Discover (`lib/mapSize.ts`, `EventMap.tsx`)

Sintomo (segnalato su Safari): la cartografia compare solo in un rettangolo piccolo, in alto a sinistra, e il resto dell'area mappa resta vuoto. Lo stato dichiarato è `ready` (la mappa disegna qualcosa e non c'è nessun errore di tasselli), quindi nessun recupero scatta.

**Riprodotto, non solo ipotizzato**, su WebKit 27.2 (build di Playwright: Safari 27.0.1 non è pilotabile finché «Allow remote automation» è spento nelle impostazioni Sviluppatore). Sequenze casuali ripetibili (con seme) di resize e di passaggi Discover ↔ altra pagina (clic, `back`, `forward`), con la mappa in produzione. Uno stato conta come difetto solo se **persiste** oltre 2 s da quando l'host è tornato visibile. Build di partenza: 1 sequenza su 170 (sempre la stessa, seme 1107, 4 volte su 4) e 2 iterazioni su 220 nello stress mirato. Build corretto: 0 su 170 e 0 su 220.

**Causa, dimostrata con una traccia** (consegne del `ResizeObserver`, scritture di `canvas.width` con la pila delle chiamate, timer da 50 ms):

1. Fuori da `/` Discover è `hidden` (`display: none`): il contenitore misura 0×0 e, se MapLibre ridimensiona in quel momento, `_containerDimensions()` ripiega su 400×300.
2. MapLibre non ascolta `window.resize`: usa solo il proprio `ResizeObserver`, che chiama `resize()` tramite un throttle da 50 ms (una chiamata subito e una finale con `setTimeout`). La chiamata finale può scattare, spesso in ritardo perché il thread è occupato, **mentre Discover è di nuovo nascosto**.
3. Se Discover torna visibile prima che il browser disegni un fotogramma, per il `ResizeObserver` la dimensione è identica all'ultima notificata e **non notifica più**: né l'osservatore di MapLibre né quello dell'app. Altri osservatori della pagina continuano a ricevere notifiche; quello del contenitore della mappa no. Il canvas resta a 400×300.

**Correzione**, additiva (`EventMap.tsx`): 200 ms dopo ogni ritorno di Discover (`active` → `true`) si confronta il canvas con il contenitore; se il contenitore è visibile e le dimensioni differiscono di più di 1 px si chiama `map.resize()` (`canvasNeedsResize`, funzione pura con test). Stessa istanza, nessun tentativo né ricreazione, nessuna copertura della mappa; selezione, inquadratura, camera e marker non si toccano (il marcatore selezionato atterra nello stesso punto prima e dopo, anche dopo un ciclo nascondi/mostra). Nel caso normale il `ResizeObserver` ha già riallineato la mappa e il controllo non fa nulla.

**Non verificato**: Safari vero. Il sintomo segnalato potrebbe avere un altro innesco (per esempio una scheda in secondo piano); il controllo riallinea comunque il canvas a ogni ritorno su Discover. Se si ripresenta, per diagnosticarlo servono questi valori dalla console di Safari:

```js
const c = document.querySelector(".event-map__canvas"), cv = c?.querySelector("canvas");
console.table({ finestra: `${innerWidth}×${innerHeight}`, dpr: devicePixelRatio, contenitore: c && `${c.clientWidth}×${c.clientHeight}`,
  canvasCss: cv && `${cv.style.width}×${cv.style.height}`, canvasBuffer: cv && `${cv.width}×${cv.height}`,
  hostNascosto: document.querySelector(".discover-host")?.hidden, stato: document.querySelector(".event-map")?.dataset.status });
```

## Ambient Map (`components/layout/AmbientMap.tsx`, `AppShell.tsx`)

Decisione: **sfondo cartografico stilizzato derivato dai token**, non una seconda mappa e non la mappa di Discover riusata.

- Discover resta l'unica istanza MapLibre, montata alla prima visita e poi nascosta: vista, filtro e selezione tornano intatti. Nessuna mappa nascosta
  intercetta click o scroll.
- Fuori da Discover un elemento fisso con gradienti CSS (verde dei parchi, azzurro dell'acqua, trama di strade) dà la sensazione di app. Non contiene dati
  geografici, non fa richieste, non costa nulla per frame. L'intensità segue la pagina (chat più discreta, form più quieti) e sparisce con `prefers-contrast: more`.
- Perché non riusare la mappa viva: su una pagina aperta direttamente (es. `/chats` dopo un refresh) avrebbe obbligato a scaricare MapLibre (~290 kB gzip) e
  a fare le richieste MapTiler solo per uno sfondo; e avrebbe richiesto l'attribuzione di MapTiler/OSM su ogni pagina.
- L'header è lo stesso su ogni pagina (pillole di vetro fluttuanti) e il suo indicatore attivo **scorre** tra le voci (`hooks/useSlidingIndicator.ts`,
  usato anche da Bottom Navigation, filtri categoria e `SegmentedControl`). Ogni pagina entra con opacità e traslazione (280 ms); con "riduci animazioni"
  gli ingressi non esistono.

## Luoghi (`lib/placeDetails.ts`, `lib/geocoding.ts`, `hooks/usePlaceResolution.ts`)

Si sceglie un luogo una volta sola; il resto si recupera.

- Una sola richiesta di ricerca `types=poi,address,place` (prima due), con cache in memoria di 10 minuti.
- Indirizzo del luogo, in ordine di affidabilità: tag `addr:street`/`addr:housenumber`, pezzo di `place_name` che somiglia a una via, via del contesto.
  Un civico compare solo se ha la forma di un civico (`21n04`, `2c01` non lo sono). I testi `road.*` del contesto sono identificatori interni e non si usano mai.
- Quartiere: voce `place.*` del contesto marcata `quarter`/`neighbourhood`/`suburb`. I municipi non sono quartieri.
- Se manca qualcosa: **un solo** reverse geocoding (`types=address&limit=1`, cache per punto). Il civico trovato è di un palazzo vicino, quindi per un luogo
  cercato per nome si usa al massimo la via (entro 60 m, entro 160 m per parchi, piazze e monumenti); per un punto toccato sulla mappa il civico vale solo entro 25 m.
- Un monumento o un parco senza una via vicina usa il proprio nome come indirizzo descrittivo. Per tutto il resto il campo resta da compilare a mano, con il motivo.
- Le coordinate non si modificano mai; il backend continua a limitare l'area a Milano.

## Data e ora (`lib/romeTime.ts`, `lib/quickCreateSchedule.ts`)

- Giorno e ora sono **sempre quelli di Milano (Europe/Rome)**, qualunque sia il fuso del dispositivo. Il payload resta un istante ISO UTC (contratto invariato).
- Il campo `datetime-local` del form completo ora significa "ora di Milano" (anche in modifica), con il fuso dichiarato nell'etichetta.
- **Ora che non esiste** (ultima domenica di marzo, 02:00–02:59): errore chiaro, niente istante. **Ora doppia** (ultima domenica di ottobre, 02:00–02:59):
  Quick Create chiede quale delle due (ora legale o solare); il form completo usa la prima.
- Scorciatoie 15:00 / 19:00 / 21:00 filtrate sul giorno scelto (mai orari passati), oltre a una ruota ore/minuti a 24 ore (`components/ui/WheelPicker.tsx`):
  scorrimento nativo con snap, ruolo `spinbutton`, frecce, PageUp/PageDown, Home/End, cifre digitate, trascinamento col mouse, clic su una riga. Dove non c'è
  `scroll-snap` si mostra il campo `time` nativo.
- Descrizione facoltativa in Quick Create: se vuota, il payload contiene il titolo già validato (3–120 caratteri) e la schermata lo dice.

## "Visualizza sulla mappa"

`<ViewOnMapLink>` naviga a `/` con lo stato `{ mapFocus: { eventId, requestedAt } }` (non nell'URL). Discover lo gestisce una volta, lo toglie dalla voce di
cronologia (Indietro/Avanti non rifanno il focus), seleziona l'evento sulla mappa già aperta e lo inquadra (zoom minimo 13, mai oltre ciò che serve).
Se un filtro categoria lo nasconde, il filtro viene tolto con un avviso e **Ripristina**; se l'evento non c'è più, un avviso lo dice. Presente nel dettaglio
evento, in "Le mie uscite" (eventi creati dall'utente) e nella "Mia agenda" del profilo (eventi a cui partecipa o che ha segnato con "Mi interessa"); Quick Create avvisa quando il filtro attivo nasconde l'uscita appena pubblicata.

## "Le mie uscite" e "La mia agenda"

- **Le mie uscite** (`/profile/events`): gli eventi che l'utente ha creato o organizza (pubblicati, annullati, gestione).
- **La mia agenda** (profilo, `ProfileParticipations`): gli eventi a cui parteciperà ("Parteciperò") o che ha segnato con "Mi interessa".
- Cambiano solo i testi: titoli, navigazione, stati vuoti, pulsanti ed etichette accessibili. Nessuna API, nessun dato e nessun URL cambia di significato.

## Il tuo stato su mappa e lista (`ParticipationIndicator.tsx`, `context/MyParticipationsContext.tsx`)

- Sul marker di un evento a cui l'utente ha risposto compare un piccolo simbolo: **cuore** a contorno per "Mi interessa", **spunta** piena per "Parteciperò". Il colore principale del marker resta quello della categoria.
- Il simbolo è decorativo (`aria-hidden`); lo stato è nel nome accessibile del marker («… (parteciperai)», «… (ti interessa)»). I marker con stato stanno sopra i vicini quando si sovrappongono.
- Mostra **solo lo stato personale** dell'utente autenticato, preso da `GET /api/users/me/participations`. Da anonimo non c'è nessun simbolo e non si fa nemmeno la richiesta. Se la richiesta fallisce non si mostra nulla: mai un indicatore inventato.
- Si aggiorna subito dopo una modifica fatta sul dettaglio dell'evento (`EventParticipationPanel` applica la risposta confermata dal servizio) e al ritorno su Discover; il marker viene aggiornato, non ricreato.
- Stessa informazione, come etichetta, nelle righe della lista e nell'anteprima.

## Discover su mobile: nessuno scorrimento, bottom sheet, navigazione (`AppShell.tsx`, `layout.css`, `lib/bottomSheet.ts`, `hooks/useBottomSheet.ts`)

**Layer che scivolavano (Safari iOS).** Su Safari `100vh` è l'altezza con le barre ritirate, più grande dell'area visibile; `body` aveva `min-height: 100vh` mentre `.discover` è `100dvh`. Il documento aveva quindi un piccolo scorrimento fantasma e bastava trascinare su un overlay (header, filtri, nav) perché mappa, filtri e "Mostra lista" scivolassero rispetto alle barre `fixed`. La posizione restava anche dopo login → Discover (nessun reset dello scroll). Non erano la causa MapLibre (con pan e zoom abilitati il contenitore ha `touch-action: pinch-zoom`), né transform sugli antenati, né z-index.

- Con Discover attivo `AppShell` imposta `html[data-view="discover"]` (e azzera lo scroll): `html` e `body` hanno `overflow: hidden`, `overscroll-behavior: none` e `body` `min-height: 0`; la shell è `position: fixed; inset: 0`. Il documento non ha estensione né rimbalzo, e anche uno `scrollTo` programmatico non sposta più nulla.
- L'altezza di `main`, `.discover-host` e `.discover` è `100%` della shell fissa (non più `100dvh` a parte): shell e mappa non possono divergere quando Safari mostra o nasconde la barra degli indirizzi.
- `body` usa `min-height: 100dvh` (con ripiego `100vh`) anche sulle altre pagine: niente più scorrimento fantasma sulle pagine corte.

**Bottom sheet della lista (< 48rem).** Tre posizioni: **intermedia** (all'apertura, ~55 % dell'area mappa, minimo 20rem), **espansa** (dai filtri al bordo basso) e **chiusa** (la sheet si smonta e torna "Mostra lista"). Sotto 48rem l'hook `useBottomSheet` gestisce i gesti; da 48rem la lista è il pannello laterale di prima, senza maniglia né trasformazioni.

- Il pannello ha sempre l'altezza dello stato espanso e si sposta solo con `transform: translate3d(0, var(--sheet-y), 0)`. `--sheet-y` è scritta direttamente sull'elemento: nessun render React né layout durante il trascinamento. La parte fuori schermo nello stato intermedio è compensata dal padding della lista (`--sheet-hidden`), così l'ultimo evento si raggiunge.
- Si trascinano maniglia (un vero `button`: il tocco alterna intermedia/espansa) e intestazione. Un gesto che parte sulla lista è della sheet se: il dito va in basso e la lista è al primo elemento; il dito va in alto e la sheet non è espansa (prima si apre, poi scorre). Altrimenti scorre la lista. Un nuovo gesto durante una transizione ferma la sheet dov'è.
- Rilascio (`lib/bottomSheet.ts`, funzioni pure con test): un lancio oltre 0,45 px/ms sposta di uno stato, altrimenti vince lo stato più vicino alla posizione proiettata. Sopra lo stato espanso c'è resistenza elastica. Sugli schermi bassi (orizzontale) lo stato intermedio non esiste.
- Solo listener locali al pannello: `touchmove` non passivo (l'unico modo per fermare lo scorrimento nativo quando il gesto è della sheet) e Pointer Events per il mouse sulla maniglia. Nessun listener su `document`/`window` oltre all'Escape già esistente. La sheet non usa `.content-reveal` (che tiene `transform` a fine corsa e la bloccherebbe). Con "riduci animazioni" non c'è transizione.
- Limite noto: se la lista era scorsa e il dito torna in cima senza staccarsi, il controllo passa alla sheet solo se il browser lascia annullare il `touchmove` (Chrome no durante lo scroll nativo, Safari non verificato); altrimenti serve un nuovo gesto.

**Bottom Navigation.** Capsula più bassa (~63 px contro 71, −11 %; voci 176×51 px: area touch ≥ 44 px) con token condivisi `--mobile-nav-h` e `--mobile-nav-bottom` (safe area iOS); usati anche da `--discover-bottom` e dalle azioni fisse del form evento. Con la sheet aperta esce di scena (`transform`, `opacity`, `visibility: hidden`: niente tocco, focus o lettori di schermo) e torna appena la sheet comincia a chiudersi (`data-state="closing"`). La regola è CSS puro con `:has()` (iOS 15.4+): senza `:has()` la capsula resterebbe visibile sotto la sheet. Il suo rettangolo esce dalla mappa, quindi la camera (`measureCameraInsets`) non la conta.

**Popover «Milano».** Agganciato alla pillola, con Crea e profilo a destra finiva a `left: -79px`, fuori schermo (utente autenticato). Sotto 48rem ora è `fixed` sotto le pillole, con margini di 0,75rem e `max-height` che non invade la nav; lo sfondo è quasi opaco (0,97) perché filtri e marker non trasparissero attraverso il testo. Da anonimo lo stack era già corretto: la copertura segnalata non si è riprodotta.

**Evidenza reale (Safari iOS 26, iPhone 13 Pro, sito pubblico con il build precedente).** Cinque screenshot confermano la diagnosi: la mappa finisce ~95 px sopra il bordo basso (sotto resta il colore del `body`) e i filtri sono tagliati in alto, cioè il documento era scorso di `100vh − 100dvh` mentre header e nav `fixed` restavano fermi; in un altro la pagina è tirata in basso (rimbalzo) con la mappa che inizia più in basso del bordo; il popover «Milano» da autenticato esce a sinistra e il testo è illeggibile. Il CSS pubblicato in quel momento non conteneva ancora `event-list-panel__grip` né `--mobile-nav-h`.

**Verificato / non verificato.** WebKit 27.2 (Playwright, profilo iPhone 13 Pro) e Chrome con viewport iPhone (tocco CDP reale per i gesti); le prove per `100vh` grande usano un `min-height` forzato. **Safari reale non è stato verificato** (rimbalzo, barra dinamica, scroll con inerzia, blur): da provare su iPhone con la barra di Safari visibile e ritratta, i gesti della sheet e il popover da loggati e da anonimi.

## Chat mobile

Sotto 48rem la conversazione occupa lo spazio tra header dell’app e Bottom Navigation. Header della conversazione e composer sono elementi fissi del flex layout; soltanto `.chat-thread__messages` scorre. L’altezza parte da `100dvh` e viene aggiornata con `visualViewport` quando compare la tastiera virtuale, includendo safe area e spazio della navigazione inferiore.

Il pulsante «Ultimi messaggi» sta in `.chat-thread__jump-layer`, uno strato `absolute` trasparente agli eventi che ha lo stesso `scrollbar-gutter` dell'area messaggi e lo centra con flex. Prima era centrato con `left: 50%` + `transform: translateX(-50%)`: `.content-reveal` anima `transform` con `animation-fill-mode: both`, quindi `translateY(0)` sovrascriveva il `translateX` e il pulsante finiva a destra di metà della propria larghezza (≈ 79 px; centrato solo con "riduci animazioni"). Lo strato spiega anche le scrollbar classiche: il pulsante segue il centro del contenuto, non quello del contenitore.

La textarea cresce automaticamente fino a 144px. Un `ResizeObserver` conserva il fondo soltanto quando l’utente era già sugli ultimi messaggi; la ricezione di un messaggio non interrompe invece la lettura dello storico.

## Verifiche

```bash
cd frontend
npm test            # tutti i tests/**/*.test.mjs (node:test): luoghi MapTiler, fuso di Milano, orari, navigazione, ALT, scroll chat, salute e recupero della mappa, guida, stato Link del profilo pubblico, dimensione della mappa, bottom sheet, splash e movimento del logo, manifest e icone
npm run typecheck
npm run build
```

Le verifiche nel browser (Chrome, API in sola lettura) non fanno parte del repository: nessuna scrive sul database di sviluppo.

# Onboarding e assistenza (`lib/guidance.ts`, `context/ProductGuidanceContext.tsx`, `styles/guidance.css`)

**Prima visita (anonima o autenticata, è lo stesso).** Sequenza: splash → onboarding (SCOPRI / CONNETTITI / VIVI) → app. Quando lo splash di avvio è uscito del tutto (ha completato il ciclo del logo e la dissolvenza, vedi sotto; segnale `splashGone`) e si è su Discover, dopo 0,32 s (`WELCOME_DELAY_MS`) compare l'onboarding a tre schermate, con **Scopri come funziona** (nell'ultima schermata: avvia il tour) ed **Esplora Milano**. Non compare mai sotto lo splash né lo interrompe a metà. Non aspetta la mappa: mentre si legge, la mappa si carica dietro.

- **Scopri come funziona** (ultima schermata dell'onboarding) avvia il tour contestuale. I passi sono risolti guardando il DOM: compaiono solo i controlli che esistono davvero in quel momento (su mobile e su desktop sono diversi). Ogni passo ha un callout con freccia che punta all'elemento evidenziato, **Avanti**, **Indietro** e **Salta**; tastiera: frecce, Esc, Tab trattenuto nel callout. Il callout non copre l'elemento che spiega (si sposta sul lato libero o nella metà opposta dello schermo).
- **Salta**, Esc ed **Esplora Milano** chiudono e segnano il benvenuto come visto: non ricompare da solo ai refresh successivi.
- Mai in automatico su Login o Registrazione (né su altre pagine): compare solo su Discover, anche se ci si arriva dopo l'accesso.
- **Riaprire il tour**: il pulsante «?» (Centro assistenza) offre «Tour dell'interfaccia» e le schermate «Come funziona LINKR».
- Con `prefers-reduced-motion` non ci sono ingressi animati né transizioni del callout.

**Persistenza.** Riusa `localStorage`. La chiave `linkr:onboarding:v1:anonymous` = `complete` è quella **del dispositivo** e la scrive ogni completamento, anche da autenticati (che scrivono in più `linkr:onboarding:v1:user:<id>`, letta solo per chi aveva già completato con la vecchia regola). Quindi l'onboarding non dipende dal login: chi lo ha visto da anonimo non lo rivede dopo l'accesso, chi lo ha visto da autenticato non lo rivede dopo il logout né con un altro account sullo stesso browser. Senza `localStorage` scrivibile non si insiste: nessuna guida automatica. Il completamento è locale al browser e **non viene sincronizzato tra dispositivi**: non si aggiungono API o colonne al backend.

Le illustrazioni delle schermate «Come funziona LINKR» sono componenti grafici CSS originali e provvisori, senza richieste remote; possono essere sostituite da asset editoriali mantenendo invariata la struttura accessibile.


# Splash di avvio e caricamento della mappa (`lib/appBoot.ts`, `lib/splashCycle.ts`, `lib/mapLoading.ts`, `components/brand/LinkrLogo.tsx`, `components/ui/LinkrSplash.tsx`, `components/ui/BootSplash.tsx`, `styles/splash.css`)

**Logo animato.** `LinkrSplash` è il componente riutilizzabile (sfondo caldo, logo nero: due mani e wordmark «linkr»). Il logo è fatto solo di tracciati SVG (nessun font, nessuna immagine) ed è composto da tre strati HTML sovrapposti (`LinkrLockup`: mano in basso, mano in alto, wordmark) con lo stesso riquadro. Si animano solo `transform` e `opacity` degli strati, quindi se ne occupa il compositore e non dipende dal thread principale, occupato all'avvio (router, eventi, mappa). Animare i gruppi SVG interni, la prima versione, costringeva il browser a ricalcolare layout e ridisegnare a ogni fotogramma (misurato su Chrome con GPU reale: circa 390 layout e 870 paint in 8 s contro circa 19 e 120 con gli strati HTML; l'aspetto è identico).

**Il ciclo (1,6 s, `--linkr-cycle` in `splash.css`)**, uguale per splash e loader:

| Fase | Quota del ciclo | Movimento |
|---|---|---|
| Aperto | 0–10 % | fermo, wordmark leggibile (**è anche il primo fotogramma**: il logo è completo fin dall'inizio, senza entrata animata) |
| Chiusura | 10–38 % | le mani si avvicinano in modo speculare (la mano in alto scende, quella in basso sale) e accelerano fino a toccarsi |
| Compressione | 38–47 % | proseguono leggermente oltre il contatto: le dita a L e lo spazio centrale sono assorbiti dalla massa nera |
| Riapertura | 47–80 % | si separano in modo morbido, con una lievissima sovra-apertura |
| Assestamento | 80–90 % | tornano nella posizione di riposo |
| Aperto | 90–100 % | riposo, prima del ciclo successivo |

Il wordmark sparisce (opacità e scala) tra il 22,5 % e il 29 % del ciclo, cioè **prima** che le mani lo sfiorino, ed è nascosto per tutta la compressione; ricompare tra il 54 % e il 58 %, **solo** quando lo spazio centrale è di nuovo sufficiente. Lo stato aperto di inizio e fine ciclo coincide, quindi il ciclo si ripete senza salti. Il logo resta centrato perché le due mani si muovono dello stesso tratto.

**Splash di avvio.**
- Compare **una volta per caricamento della pagina**, mai nelle navigazioni interne, e resta finché l'app non è pronta: il router ha inizializzato la prima pagina (anche una pagina `lazy`) e, se si atterra su Discover, la prima richiesta degli eventi si è conclusa (con un elenco o con un errore). Nessun ritardo artificiale oltre al completamento del ciclo.
- **Uscita coordinata con il ciclo.** Quando l'app è pronta lo splash non si interrompe a metà: attende che le mani siano di nuovo aperte (dal 90 % del ciclo, `splashExitDelayMs` in `splashCycle.ts`), poi sfuma in 240 ms con il logo fermo e aperto. L'attesa non supera mai un ciclo.
- **Primo avvio sul dispositivo** (chiave `linkr:splash:seen:v1` in `localStorage` assente): il ciclo parte subito e se ne vede almeno uno intero, quindi lo splash dura circa 1,7 s anche con l'API veloce (misurato in emulazione WebKit e Chrome). La chiave si scrive quando lo splash comincia a uscire.
- **Avvii successivi:** il logo resta fermo e aperto per 350 ms (`SPLASH_RETURN_DELAY_MS`) prima del ciclo. Se l'app è pronta prima, o durante la breve sosta iniziale a mani aperte, lo splash esce subito (circa 0,4 s in tutto, senza movimento); se è pronta a ciclo avviato aspetta la fine del ciclo in corso. Senza `localStorage` disponibile (per esempio in alcune modalità di navigazione privata) si considera già visto: avvio rapido.
- Non copre mai un errore: dopo 4 s compare «Il server si sta avviando…» (utile col backend gratuito che si risveglia) e dopo 8 s lascia comunque spazio all'app, al termine del ciclo in corso. Un errore di rendering fa uscire lo splash allo stesso modo (al termine del ciclo in corso, non più istantaneamente).
- Il browser scarica prima il JavaScript: fino a quel momento si vede solo il fondo caldo della pagina (`index.html` lo imposta con uno stile inline, così non c'è un lampo bianco; nessuno splash HTML duplicato).
- **L'onboarding aspetta l'uscita completa dello splash** (`splashGone`, vedi la sezione precedente), quindi non lo interrompe né ci si sovrappone.
- `prefers-reduced-motion`: il logo resta fermo, aperto e con il wordmark leggibile; nessuna animazione né dissolvenza, e lo splash sparisce subito quando l'app è pronta.

**Mappa in caricamento.** La mappa di Discover resta montata tra le pagine (una sola istanza MapLibre): tornare su Discover non ricrea nulla e non mostra overlay. L'overlay LINKR (`LinkrSplash variant="panel"`) usa lo **stesso logo e lo stesso ciclo dello splash**, più piccolo (5,5 rem), con il wordmark leggibile quando le mani sono aperte e nascosto durante la compressione, e il testo «Caricamento mappa» sotto. Il ciclo si ripete finché serve e ha una fase comune a tutti i loader (il segnaposto di Discover e l'overlay della mappa non fanno scattare il logo quando uno sostituisce l'altro). Copre la mappa **solo finché non ha mai disegnato**: da `loading`/`recovering` (inclusi i due tentativi automatici) fino a `ready` (evento `load` + `idle`, nessun errore di tasselli, `queryRenderedFeatures` non vuoto: la logica di `mapRecovery.ts`, invariata). Quando è pronta svanisce subito in 240 ms, **senza aspettare la fine del ciclo** (non ritarda la mappa; il logo continua a muoversi mentre sfuma) e i marker compaiono con lei. Con `prefers-reduced-motion` il logo è fermo e aperto. Non copre mai per una selezione, un filtro, un movimento della camera o un recupero successivo (resta la pillola «Ricarico la mappa…»).

- Dopo 20 s (`slow` di `mapRecovery.ts`) l'overlay dice «La mappa sta impiegando più del previsto…» e offre **Riprova**. Un timeout non dichiara mai pronta la mappa.
- Errore (stile non scaricabile, WebGL assente, chiave mancante): schermata «Mappa non disponibile» con il messaggio e **Riprova**; con «Riprova» l'overlay ritorna finché la mappa non disegna.
- Cartografia incompleta dopo i tentativi (`degraded`): si vede la mappa con l'avviso e **Riprova**, come prima (marker e lista restano usabili).
- Correzione collegata: la regola CSS che nasconde i marker finché la mappa non è pronta non funzionava (MapLibre scrive `opacity` inline sul marker): ora usa `!important`.

# Header: logo e rotazione (`components/layout/AppHeader.tsx`, `components/ui/BrandMark.tsx`, `styles/layout.css`)

Il logo completo (simbolo delle mani e wordmark «linkr», entrambi tracciati SVG) sta nella pillola in alto a sinistra ed è un link a `/` (`aria-label` «LINKR home»).

- **Da una pagina diversa da Discover** il tocco naviga a Discover come un normale link, senza nessuna animazione che ritardi la navigazione.
- **Già su Discover** un clic o tocco semplice (tasto sinistro, senza Ctrl/Cmd/Shift/Alt) **non naviga e non ricarica**: il solo **simbolo delle mani** ruota di 360° attorno al proprio centro in **540 ms** (`brand-spin`, `cubic-bezier(0.6, 0, 0.2, 1)`) e torna esattamente nella posizione di partenza. Il wordmark, la pillola e il contenitore restano immobili e le dimensioni dell'header non cambiano; mappa, filtri, selezione e URL non si toccano. I clic con modificatori mantengono il comportamento normale del browser.
- **Tocchi ripetuti:** durante il giro i tocchi successivi vengono ignorati (un solo giro per volta, nessuna animazione accumulata); se `animationend` non arrivasse, il blocco si toglie comunque dopo 700 ms.
- **Accessibilità:** resta un normale link, raggiungibile con Tab e attivabile con Invio (il giro parte anche da tastiera, il focus resta sul link). Con `prefers-reduced-motion` il simbolo non ruota e, su Discover, il tocco non fa nulla (non naviga comunque).
