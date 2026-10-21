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
- Un errore di tassello, glifo o sprite non è più fatale: la mappa mostra «non disponibile» solo se lo stile non si scarica, e offre **Riprova**.

Misure (Chrome con WebGL software, quindi solo relative):

| | prima | dopo |
|---|---|---|
| layer dello stile | 164 | 106 |
| layer `symbol` (etichette/icone) | 59 | 22 |
| layer 3D | 1 | 0 |
| richieste MapTiler al primo caricamento | 21 | 20 |
| byte MapTiler (stile, sprite, glifi, tasselli) | 1442 kB | 1364 kB |
| tempo medio per frame durante un pan | 78 ms | 58 ms (67 ms con il pannello in vetro) |

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
evento, in "I miei eventi" e nell'agenda del profilo; Quick Create avvisa quando il filtro attivo nasconde l'uscita appena pubblicata.

## Verifiche

```bash
cd frontend
npm test            # 45 test: luoghi MapTiler (risposte reali), fuso di Milano (anche con dispositivo a New York/Tokyo/Auckland), orari, navigazione
npm run typecheck
npm run build
```

Le verifiche nel browser (Playwright + Chrome, sessione finta, API in sola lettura e mock fabbricati per le scritture) non fanno parte del repository: nessuna scrive sul database di sviluppo.
