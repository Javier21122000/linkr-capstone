# Upload delle immagini di copertina degli eventi

Un utente autenticato carica un'immagine e usa l'URL restituito nel campo `imageUrl` dell'evento.
Nessuna modifica al database: le immagini stanno su disco, in una cartella esterna al repository.

## API

### `POST /api/uploads/event-images` (richiede il JWT)

`multipart/form-data`, campo `file`. Risposta `201`:

```json
{
  "imageUrl": "/api/uploads/event-images/0b8f0c3e-6f0a-4d6e-9d5a-3f3e1f6f2a11.jpg",
  "filename": "0b8f0c3e-6f0a-4d6e-9d5a-3f3e1f6f2a11.jpg",
  "contentType": "image/jpeg",
  "size": 123456
}
```

`imageUrl` e' un percorso relativo alla radice del sito: va copiato **cosi' com'e'** nel campo `imageUrl` di
`POST /api/events` o `PUT /api/events/{id}`. `contentType` e' quello rilevato dai byte, non quello dichiarato.
`size` e' la dimensione del file **salvato**, cioe' dopo la rimozione dei metadati (vedi sotto): puo' essere minore di quella inviata.

| Stato | Quando |
|---|---|
| 400 `Invalid upload` | file assente o vuoto; richiesta non multipart o malformata |
| 400 `Invalid image` | e' JPEG/PNG/WebP ma danneggiato, troncato, con dati in coda, animato o con dimensioni eccessive |
| 401 | JWT assente, scaduto o non valido |
| 413 `Upload too large` | oltre 5 MB (file) / 6 MB (richiesta) |
| 415 `Unsupported image` | non e' JPEG/PNG/WebP (SVG, GIF, HTML, PDF...) oppure il MIME dichiarato non coincide con i byte |
| 429 `Too many uploads` | oltre 20 tentativi all'ora o 60 al giorno per utente |

### `GET /api/uploads/event-images/{filename}` (pubblico)

Sola lettura, solo per i file caricati con l'endpoint sopra. Qualsiasi altro nome, percorso o file → `404`/`4xx`.
Non esiste l'elenco della cartella.

### Compatibilita' con `EventRequest.imageUrl`

`imageUrl` accetta **due** forme, e solo queste:

1. un URL esterno `https://...` (invariato);
2. l'immagine caricata: esattamente `/api/uploads/event-images/<uuid minuscolo>.<jpg|png|webp>`, e il file deve **esistere**
   nello storage (altrimenti `400` con `errors.imageUrl`).

`http://`, `//host`, altri percorsi relativi, parametri o frammenti restano rifiutati. `imageAlt` resta obbligatorio.

## Configurazione

| Proprieta' | Variabile | Default |
|---|---|---|
| `linkr.uploads.dir` | `LINKR_UPLOAD_DIR` | `~/.linkr/uploads` (le immagini vanno in `<dir>/event-images/`) |
| `linkr.uploads.max-per-hour` | | 20 |
| `linkr.uploads.max-per-day` | | 60 |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | | 5MB / 6MB |

La cartella si crea al primo upload (permessi `700` dove possibile). Se `LINKR_UPLOAD_DIR` punta **dentro** la copia di
lavoro di Git il backend non parte, con un messaggio esplicito. Nei test la cartella e' sempre temporanea.

## Metadati rimossi (EXIF, GPS, XMP...)

Una foto scattata col telefono contiene la posizione di chi l'ha fatta, data, modello del dispositivo, a volte una miniatura o
un'immagine secondaria. **Il server non salva mai il file ricevuto**: salva solo la versione ripulita da `ImageMetadataSanitizer`,
che lavora sul contenitore del file (segmenti JPEG, chunk PNG, chunk RIFF/WebP) **senza ricodificare i pixel**: nessuna perdita di
qualita' e nessun decoder esposto a file ostili. E' una *lista di ammissione*: cio' che non e' elencato qui non viene copiato.

| Formato | Resta | Sparisce |
|---|---|---|
| JPEG | tabelle, frame, scansioni; APP14 "Adobe" (CMYK); profilo colore ICC valido | EXIF (GPS, data, modello, miniatura), XMP, IPTC/Photoshop, JFIF, commenti, **tutto cio' che segue il primo EOI** (immagini secondarie MPF dei telefoni) |
| PNG | IHDR, PLTE, IDAT, IEND, tRNS, gAMA, cHRM, sRGB, cICP, iCCP (max 256 KB) | tEXt/zTXt/iTXt (anche XMP), eXIf, tIME, pHYs, chunk privati, parti animate (APNG: resta l'immagine statica) |
| WebP | VP8/VP8L, ALPH, ICCP valido | EXIF, XMP e ogni altro chunk |

- **Orientamento.** Le foto verticali dei telefoni sono "ruotate via EXIF": cancellarlo le mostrerebbe di lato. Dell'EXIF si legge
  **una sola informazione**, l'orientamento (1-8); se non e' quello neutro la si riscrive in un EXIF minimo costruito dal server
  (26 byte di TIFF con la sola voce Orientation). Nulla dell'EXIF originale viene copiato. In Chrome le foto JPEG e PNG appaiono
  con la stessa rotazione di prima; per i WebP Chrome ignora l'orientamento EXIF sia prima sia dopo, quindi il risultato visibile
  non cambia.
- **Profilo ICC.** Si conserva perche' serve ai colori (le foto iPhone sono Display P3: senza profilo apparirebbero spente) e non
  identifica chi ha scattato. Si accetta solo se e' plausibile (dimensione dichiarata coerente, firma `acsp`, al massimo 256 KB).
- **Il limite di 5 MB vale per il file ricevuto.** Un file di 5 MB con metadati pesanti puo' essere molto piu' piccolo una volta ripulito.
- **Difesa in profondita'.** Dopo la riscrittura il risultato deve superare di nuovo `ImageInspector` con lo stesso formato e le stesse
  dimensioni; altrimenti l'upload e' rifiutato (`400 Invalid image`) e non si pubblica nulla.
- **Immagini gia' presenti nello storage:** *non* vengono toccate. Quelle caricate prima di questa modifica possono ancora contenere
  metadati (vedi "Limiti residui").

## Sicurezza

- **Contenuto reale**: il formato si decide dai byte (firma + struttura completa: CRC dei chunk PNG, fine file JPEG,
  RIFF coerente per WebP). Nome e MIME dichiarati non contano; il MIME, se presente, deve coincidere con il contenuto.
- **Dimensioni**: massimo 5 MB, 8000 px per lato e 40 megapixel (le dimensioni si leggono dalle intestazioni, senza decodificare).
- **Nomi**: UUID casuale generato dal server + estensione canonica. Il nome originale non entra mai in un percorso ne' nei log.
- **Nessuna sovrascrittura**: scrittura in file temporaneo e `move` senza `REPLACE_EXISTING`.
- **Lettura**: nome validato con regex esatta, risolto sotto la cartella base, solo file regolari (no link simbolici).
- **Risposta di lettura**: `Content-Type` fisso, `X-Content-Type-Options: nosniff`,
  `Content-Security-Policy: default-src 'none'; sandbox`, `Cross-Origin-Resource-Policy: cross-origin`,
  `Cache-Control: public, max-age=31536000, immutable`.

## Limiti residui noti

1. **Immagini caricate PRIMA della rimozione dei metadati.** Le nuove immagini sono ripulite (vedi sopra); quelle gia' nello
   storage no. Prima della pubblicazione: o si usa uno storage vuoto, oppure si ripuliscono i file esistenti con una procedura
   dedicata, da eseguire solo dopo un backup e con l'autorizzazione del proprietario dei dati.
2. **File orfani.** Un'immagine caricata e mai usata (evento non creato, immagine sostituita, evento cambiato) resta su disco.
   Non c'e' cancellazione automatica. Procedura **manuale e in sola lettura** per elencarli:
   ```bash
   psql "$DB" -At -c "select substring(image_url from '[^/]+$') from event where image_url like '/api/uploads/event-images/%'" | sort > /tmp/usati.txt
   find ~/.linkr/uploads/event-images -type f -mtime +7 -exec basename {} \; | sort > /tmp/presenti.txt
   comm -13 /tmp/usati.txt /tmp/presenti.txt      # presenti, piu' vecchi di 7 giorni e mai usati: da controllare a mano prima di cancellare
   ```
3. **Limiti per utente in memoria**: si azzerano al riavvio e valgono per una sola istanza. Non c'e' un tetto sullo spazio totale.
4. **Nessun controllo di proprieta'**: chi conosce il nome di un'immagine gia' caricata (un UUID non indovinabile, ma pubblico una
   volta usato) puo' usarla anche nel proprio evento. Le immagini sono pubbliche per definizione.
5. **Validazione strutturale, non decodifica**: si scartano file rotti e formati diversi, ma non si garantisce che ogni pixel sia
   decodificabile; per questo la lettura e' blindata (`nosniff`, CSP `sandbox`, Content-Type fisso).
6. **Richieste enormi** (oltre ~16 MB): il rifiuto e' sempre corretto, ma il client puo' ricevere un `413` oppure vedere la
   connessione chiusa, a seconda del timing.
7. **Frontend**: `imageUrl` e' un percorso relativo (`/api/...`). Con Vite funziona tramite il proxy `/api`; con frontend e API su
   domini diversi il frontend deve anteporre l'URL base dell'API quando `imageUrl` inizia per `/api/uploads/`.
8. **Deploy**: dietro HTTPS la cartella deve stare su un volume persistente e con backup, altrimenti un nuovo deploy perde le immagini.

## Persistenza su Render: diagnosi del 24/10/2026

- **Dove:** `LINKR_UPLOAD_DIR=/data/uploads` (impostato nel `Dockerfile`), file in `/data/uploads/event-images/<uuid>.<ext>`; nel database resta solo l'URL relativo `/api/uploads/event-images/<uuid>.<ext>`.
- **Sopravvivenza:** il filesystem del container è effimero. Con l'istanza Free (nessun disco persistente) le foto spariscono a ogni riavvio, deploy o risveglio dopo lo sleep. Il database continua a puntare a file che non esistono più.
- **Verificato in produzione con sole `GET`:** delle 3 foto caricate da utenti presenti nell'elenco eventi, **3 su 3 rispondono già 404**. Le 48 copertine demo sono file del frontend e non sono interessate.
- **URL:** stabili (UUID, `Cache-Control` di un anno e `immutable`), ma un URL stabile non aiuta se il file non c'è: i browser che l'hanno già scaricata la mostrano ancora, gli altri no. La pagina mostra la copertina della categoria al posto dell'immagine mancante.
- **Rischio per una beta con utenti reali:** concreto e già avvenuto. Lo stesso vale, più in grande, per PostgreSQL Free (scade 30 giorni dopo la creazione e non ha backup, vedi `DEPLOY.md`).
- **Soluzioni minime** (nessuna è implementata): (1) disco persistente Render montato su `/data/uploads` (solo infrastruttura, nessun codice, richiede un'istanza a pagamento, una sola istanza); (2) storage oggetti esterno (S3, R2, Cloudinary…) con credenziali nuove e un piccolo adattatore nel backend; (3) fino ad allora, sconsigliare o nascondere il caricamento foto e usare le copertine per categoria.
