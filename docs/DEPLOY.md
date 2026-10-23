# Pubblicare LINKR su Render

Tutto su Render, tre servizi nella stessa regione (consigliata **Frankfurt**, la più vicina a Milano):

```
Browser ──https──> Static Site  (frontend/dist, rewrite /* -> /index.html)
   │
   ├──https──> Web Service Docker (Spring Boot)  /api/*  ──> PostgreSQL (rete interna di Render)
   └──wss────>                                   /ws
```

Frontend e API hanno **domini diversi** (`*.onrender.com`): il browser fa chiamate cross-origin, quindi servono `CORS_ALLOWED_ORIGINS` sul backend e `VITE_API_BASE_URL` sul frontend. Non c'è nessun proxy: REST, immagini e WebSocket vanno direttamente al backend.

> La versione precedente di questo documento (una sola macchina con Caddy e systemd) è nella cronologia di Git.

## 0. Prima di cominciare

- [ ] Account Render con il repository GitHub collegato (il branch `main` deve contenere `backend/Dockerfile`).
- [ ] Chiave MapTiler **pubblica** (finisce nel bundle). Nel pannello MapTiler aggiungi l'origine del sito (`https://<nome-frontend>.onrender.com`) ai referrer consentiti e imposta un tetto di richieste: se la chiave è limitata a `localhost`, mappa e ricerca luoghi non funzionano online.
- [ ] Un segreto JWT nuovo: `openssl rand -base64 48` (almeno 32 caratteri; non riusare quello locale; non metterlo in nessun file del repository).
- [ ] Scegli i **nomi** dei servizi prima di crearli: l'URL è `https://<nome>.onrender.com` (se il nome è già preso Render aggiunge un suffisso casuale; controlla l'URL reale dopo la creazione). Qui: `linkr-api` e `linkr-web`.

## 1. PostgreSQL

Dashboard → **New → PostgreSQL**.

| Campo | Valore |
|---|---|
| Name | `linkr-db` |
| Database | `linkr_db` (importante: il backend rifiuta di migrare un database con un altro nome; vedi `LINKR_DB_EXPECTED_NAME`) |
| User | `linkr` |
| Region | Frankfurt (la stessa degli altri servizi) |
| Instance type | Free |

A creazione finita, nella pagina del database (**Info → Connections**) annota *Hostname interno*, *Port*, *Database*, *Username*, *Password*. Per il backend si usa l'**host interno** (`dpg-…-a`, senza `.render.com`): è raggiungibile solo dai servizi Render della stessa regione e non richiede SSL.

L'URL JDBC si costruisce a mano, **non** si incolla quello `postgresql://…` di Render:

```
LINKR_DB_URL = jdbc:postgresql://<hostname-interno>:5432/linkr_db
```

Utente e password vanno nelle due variabili separate. Il database parte **vuoto**: Flyway applica V1–V9 al primo avvio del backend (`ddl-auto=validate`).

## 2. Backend (Web Service Docker)

Dashboard → **New → Web Service → Build and deploy from a Git repository** → il repository.

| Campo | Valore |
|---|---|
| Name | `linkr-api` |
| Language / Runtime | **Docker** |
| Branch | `main` |
| Region | Frankfurt |
| Root Directory | *(vuoto)* |
| Dockerfile Path | `./backend/Dockerfile` |
| Docker Build Context Directory | `./backend` |
| Instance type | Free |
| Health Check Path (Advanced) | `/api/health` |

Se nei log di build compare "Dockerfile not found": imposta Root Directory `backend`, Dockerfile Path `./Dockerfile` e contesto `.`. I due modi si escludono a vicenda.

### Variabili d'ambiente del backend

Si inseriscono nel pannello **Environment** del servizio (mai in file del repository). `PORT` la imposta Render; il backend la legge (`server.port=${PORT:8080}`).

| Variabile | Valore | Note |
|---|---|---|
| `LINKR_DB_URL` | `jdbc:postgresql://<host-interno>:5432/linkr_db` | vedi §1 |
| `LINKR_DB_USERNAME` | utente del database | |
| `LINKR_DB_PASSWORD` | password del database | |
| `LINKR_JWT_SECRET` | segreto generato in §0 | cambiarlo invalida tutte le sessioni |
| `CORS_ALLOWED_ORIGINS` | `https://linkr-web.onrender.com` | origine **esatta** del frontend, senza `/` finale. Serve anche al WebSocket (controlla l'`Origin`). Si imposta dopo il §3 |
| `SPRING_PROFILES_ACTIVE` | `demo` | attiva i seeder degli eventi demo (vedi sotto) |
| `LINKR_DEMO_COMMUNITY_ENABLED` | `true` | community demo: 15 personaggi, 36 uscite, partecipazioni, Link |

Facoltative: `LINKR_JWT_EXPIRATION` (default `2h`, non esiste refresh token) e `LINKR_DB_EXPECTED_NAME` (solo se il database non si chiama `linkr_db`).
**Non** impostare nulla di `LINKR_TICKETMASTER_*`: l'integrazione è spenta di base e non blocca l'avvio.

Il `Dockerfile` imposta già, senza segreti: `JAVA_OPTS` (heap al 65% della memoria del container, GC seriale), `SPRING_JPA_SHOW_SQL=false`, `SERVER_FORWARD_HEADERS_STRATEGY=framework` e `LINKR_UPLOAD_DIR=/data/uploads`. Sono sovrascrivibili dal pannello.

### Profilo `demo`

I seeder `DemoEventSeeder` (eventi demo di Milano) e `CommunityDemoSeeder` (community) si attivano con i profili `dev` **o** `demo`. Con il profilo di produzione standard (nessun profilo) non partono mai. Sono idempotenti (id fissi e `ON CONFLICT`): a ogni avvio non duplicano nulla e spostano nel futuro gli eventi demo già terminati. Gli account demo non possono fare login (nessuna password esiste): chi presenta si **registra** dal sito. Dettagli in [`COMMUNITY-DEMO.md`](COMMUNITY-DEMO.md).

Per una produzione "vera" (senza contenuti demo) basta togliere `SPRING_PROFILES_ACTIVE` e `LINKR_DEMO_COMMUNITY_ENABLED`; i dati già creati restano.

## 3. Frontend (Static Site)

Dashboard → **New → Static Site** → lo stesso repository.

| Campo | Valore |
|---|---|
| Name | `linkr-web` |
| Branch | `main` |
| Root Directory | `frontend` |
| Build Command | `npm ci && npm run build` (`npm run build` da solo non installa le dipendenze) |
| Publish Directory | `dist` |

`npm run build` esegue prima il typecheck e poi `vite build`.

### Variabili d'ambiente del frontend

Vite le **incorpora nel bundle durante la build**: cambiarle richiede un nuovo deploy del sito.

| Variabile | Valore |
|---|---|
| `VITE_API_BASE_URL` | `https://linkr-api-9ezt.onrender.com` — URL assoluto con `https://`, senza percorso |
| `VITE_MAPTILER_KEY` | la chiave pubblica MapTiler |
| `NODE_VERSION` | `24.15.0` (la versione con cui è stato provato; Vite 8 richiede Node ≥ 20.19 / 22.12) |

`VITE_MAP_STYLE` resta vuota. Il WebSocket usa la stessa `VITE_API_BASE_URL`: `https://…` diventa `wss://…/ws`, `http://…` diventa `ws://…/ws` (nessuna impostazione a parte).

### Rewrite per il routing React

Senza questa regola un refresh su `/people`, `/events/123` o `/profile` risponde 404, perché sul server esiste solo `index.html`. Nel sito → **Redirects/Rewrites → Add Rule**:

| Source | Destination | Action |
|---|---|---|
| `/*` | `/index.html` | **Rewrite** |

Render non applica la regola dove esiste già un file (`/assets/…`, `/demo/…`, `/favicon.svg`), quindi gli asset statici restano serviti normalmente. La regola non si può versionare nel repository senza un Blueprint (`render.yaml`), che qui non si usa: il database richiede un URL JDBC composto a mano.

### Chiudere il cerchio con CORS

Con l'URL definitivo del frontend, torna sul backend → **Environment** → imposta `CORS_ALLOWED_ORIGINS=https://linkr-web.onrender.com`. Render riavvia il servizio da solo. Gli URL di anteprima o un secondo dominio vanno aggiunti separati da virgola.

## 4. Smoke test (5 minuti, dopo il deploy)

Nei log del backend (**Logs**): `Database check passed`, le migrazioni Flyway, `Started BackendApplication`. Il primo avvio su Free può impiegare 1–2 minuti.

```bash
API=https://linkr-api-9ezt.onrender.com
WEB=https://linkr-web.onrender.com

curl -s $API/api/health                       # {"status":"UP","application":"LINKR","service":"backend"}
curl -s $API/api/events | head -c 300         # eventi demo (JSON)

# CORS: il preflight dal sito deve rispondere 200 con Access-Control-Allow-Origin = $WEB
curl -si -X OPTIONS $API/api/events -H "Origin: $WEB" -H "Access-Control-Request-Method: GET" | head -n 12

# WebSocket: handshake dal sito -> 101 Switching Protocols (da un'altra origine: 403)
curl -si -m 5 $API/ws -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" \
     -H "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==" -H "Origin: $WEB" | head -n 3

curl -sI $WEB/people | head -n 1              # HTTP/2 200 (rewrite attiva), non 404
curl -s -o /dev/null -w "%{http_code}\n" -X POST $API/api/events -H "Content-Type: application/json" -d '{}'   # 401
```

Nel browser (console aperta, nessun errore CORS/CSP):

- [ ] Discover mostra la mappa e i marker; la copertina di un evento demo si vede.
- [ ] Refresh diretto su `/people` e su `/events/<id>`: la pagina si apre (con sessione attiva resta loggato).
- [ ] Registrazione → login → profilo.
- [ ] Partecipa a due o tre eventi; attiva Discovery; **Discover People** mostra persone.
- [ ] Richiesta Link a un personaggio demo; con due browser, un secondo utente accetta e la chat consegna i messaggi in tempo reale (DevTools → Network → WS: `wss://…/ws`).
- [ ] Crea un evento con una foto JPEG/PNG e controlla che l'immagine si veda.

## 5. Piano gratuito: limiti da conoscere per la demo

Verificati sulla [documentazione Render](https://render.com/docs/free) il 22/10/2026:

- **Web Service Free va in sleep dopo 15 minuti senza traffico** e si risveglia in circa un minuto (qui anche JVM, Flyway e seed). Il client ha un timeout di 10 s (`frontend/src/lib/api.ts`): se apri l'app a freddo, le prime chiamate falliscono o la mappa resta vuota.
  **Procedura demo:** 10 minuti prima di presentare apri `$API/api/health` e naviga l'app finché Discover mostra gli eventi. Un piano a pagamento per il backend elimina lo sleep (prezzo da verificare sul sito Render).
- **Filesystem effimero**: le foto caricate dagli utenti spariscono a ogni riavvio, deploy o sleep (un disco persistente non è disponibile sul Free). La pagina mostra un'immagine rotta. Le copertine e gli avatar demo non sono toccati: stanno nel frontend (`/demo/…`). Non basare la demo su foto caricate prima. Con un disco a pagamento: montalo su `/data`; l'utente del container non è root, quindi verifica che `/data/uploads` sia scrivibile.
- **PostgreSQL Free scade 30 giorni dopo la creazione** e non ha backup: è adatto a un ambiente dimostrativo temporaneo, non a un servizio permanente.
- **Auto-deploy**: ogni push su `main` ridistribuisce i servizi. Prima della presentazione disattivalo (Settings → Build & Deploy → Auto-Deploy: Off) per non avere un deploy a metà demo.
- Il JWT dura 2 ore e non c'è refresh token: fai il login poco prima di presentare.
- Memoria del Free: limitata (indicativamente 512 MB). Il backend è configurato per restarci (`JAVA_OPTS`); se Render segnala "out of memory" nei log, abbassa `-XX:MaxRAMPercentage` in `JAVA_OPTS`.

## 6. Limiti accettati e miglioramenti rinviabili

- Nessuna protezione dai tentativi ripetuti su login e registrazione (esiste solo il limite di upload per utente, in memoria).
- Un solo processo: limite upload e broker WebSocket sono in memoria; un riavvio li azzera e chi è connesso si riconnette.
- `/api/health` verifica solo che il processo risponda, non il database.
- JWT in `sessionStorage` (leggibile da JavaScript). Difesa principale contro l'XSS: la CSP qui sotto.
- Le immagini caricate e mai usate restano su disco (vedi [`UPLOAD-IMMAGINI.md`](UPLOAD-IMMAGINI.md)).
- I test backend sul database di test e un giro completo con utenti reali non fanno parte di questa procedura (vedi §7 per i test).
- Decisione aperta sugli orari: data e ora si interpretano nel fuso del dispositivo ma si mostrano in `Europe/Rome`. Per chi è in Italia coincide.

### Intestazioni di sicurezza (facoltativo)

Static Site → **Headers**, percorso `/*`. La CSP seguente è stata provata sul bundle di produzione con frontend e API sullo stesso dominio; **con il backend su un altro dominio non è stata provata**: `connect-src` deve elencare l'API (HTTPS e WSS). Provala con la console aperta e, se blocca qualcosa, toglila finché la configurazione non è stata verificata.

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self' https://linkr-api-9ezt.onrender.com wss://linkr-api-9ezt.onrender.com https://api.maptiler.com; worker-src 'self' blob:; child-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(self), microphone=(), geolocation=()
```

`img-src https:` serve perché un evento può usare l'URL https di un'immagine esterna; `style-src 'unsafe-inline'` serve a MapLibre e agli stili inline di React.

## 7. Test backend sul database di test (facoltativo, in locale)

Servono utente e password di PostgreSQL nell'ambiente (non nel repository) e il database `linkr_test_db` con lo stesso utente. Da una copia, per non far riavviare un'app di sviluppo in esecuzione (devtools ricompila `backend/target`):

```bash
set -a; source .env; set +a                      # carica LINKR_DB_USERNAME / LINKR_DB_PASSWORD, senza stamparli
rsync -a --exclude target backend/ /tmp/linkr-build/ && cd /tmp/linkr-build
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test   # usa SOLO linkr_test_db (URL fisso nei test)
```

Atteso: tutto verde. Il jar di produzione si costruisce con `./mvnw -DskipTests package` (è quello che fa il `Dockerfile`).
