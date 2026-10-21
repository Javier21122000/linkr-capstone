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

## Limiti residui (noti e accettati per il Capstone)

1. **Metadati EXIF/GPS non rimossi.** Una foto scattata col telefono puo' contenere la posizione di chi l'ha scattata, e il file
   viene pubblicato com'e'. Rimuoverli richiede di ricodificare l'immagine (e applicare l'orientamento EXIF, altrimenti molte foto
   si vedrebbero ruotate); il JDK non scrive WebP. Da fare prima di un uso reale: ricodifica con una libreria dedicata.
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
