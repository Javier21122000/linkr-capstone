# Pubblicare LINKR Lite — checklist

Scelta per un Capstone: **una sola macchina, un solo dominio**, nessun container obbligatorio.

```
Browser ──https──> Caddy (HTTPS automatico, file statici di frontend/dist)
                     ├── /api/*  ──> Spring Boot 127.0.0.1:8080 ──> PostgreSQL (stessa macchina)
                     └── /ws     ──> Spring Boot (WebSocket STOMP)      └──> cartella immagini (volume persistente)
```

Stesso dominio per sito e API significa: **niente CORS da gestire**, `VITE_API_BASE_URL` vuota, WebSocket sullo stesso host.
Un PaaS (Render, Railway, Fly) va bene lo stesso se frontend e API restano dietro lo stesso dominio; evitare Kubernetes e servizi gestiti separati.

## 0. Prima di tutto (blocca la pubblicazione)

- [ ] **Test backend sul database di test.** Servono utente e password di PostgreSQL nell'ambiente (non nel repository). Da una copia,
      per non far riavviare l'app di sviluppo (devtools ricompila `backend/target`):
      ```bash
      set -a; source .env; set +a                      # carica LINKR_DB_USERNAME / LINKR_DB_PASSWORD, senza stamparli
      rsync -a --exclude target backend/ /tmp/linkr-build/ && cd /tmp/linkr-build
      JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test   # usa SOLO linkr_test_db (URL fisso nei test)
      ```
      Atteso: tutto verde. Il database `linkr_test_db` deve esistere con lo stesso utente.
- [ ] **Giro completo con utenti di prova** (richiede autorizzazione a scrivere nel database di sviluppo): registrazione → login →
      refresh → evento da form completo e da Quick Create con foto JPEG/PNG → modifica → partecipazione → Discover People →
      richiesta Link → accettazione → chat con messaggi non letti → logout.
- [ ] Decidere cosa fare con gli orari: oggi data e ora si interpretano **nel fuso del dispositivo** ma si mostrano in `Europe/Rome`.
      Per chi e' in Italia coincide; chi crea un evento da un altro fuso lo vedrebbe spostato (es. New York: "20:30" diventa 02:30 a Milano).
- [ ] Decidere se il database di produzione parte con eventi demo. Il seeder gira **solo** con il profilo `dev`: in produzione Discover e' vuoto finche' nessuno crea eventi.

## 1. Segreti e variabili (backend)

File `/etc/linkr/linkr.env`, permessi `600`, proprietario `linkr`. Mai nel repository.

| Variabile | Valore |
|---|---|
| `LINKR_DB_URL` | `jdbc:postgresql://localhost:5432/linkr_db` |
| `LINKR_DB_USERNAME` / `LINKR_DB_PASSWORD` | utente dedicato, **non** `postgres`, solo su questo database |
| `LINKR_JWT_SECRET` | nuovo, almeno 32 byte: `openssl rand -base64 48`. Cambiarlo invalida tutte le sessioni |
| `CORS_ALLOWED_ORIGINS` | `https://tuodominio.it` — serve anche con lo stesso dominio: il WebSocket controlla l'`Origin` con questa lista |
| `LINKR_UPLOAD_DIR` | es. `/var/lib/linkr/uploads` — **fuori** dal repository, sul disco persistente |
| `SPRING_JPA_SHOW_SQL` | `false` (nel file di configurazione e' `true`) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` (dietro il proxy Spring deve sapere che la richiesta e' https) |
| `SERVER_ADDRESS` | `127.0.0.1` (l'API non deve essere raggiungibile senza passare dal proxy) |
| `LINKR_DB_EXPECTED_NAME` | solo se il database non si chiama `linkr_db` (altrimenti Flyway non migra: e' una protezione voluta) |
| `LINKR_JWT_EXPIRATION` | opzionale, default `2h`. **Non esiste refresh token**: allo scadere si rifa' il login |

Non impostare `SPRING_PROFILES_ACTIVE=dev`.

## 2. Database

- [ ] PostgreSQL con utente e database dedicati; `linkr_db` creato **vuoto**: Flyway applica V1-V8 all'avvio (`ddl-auto=validate`).
- [ ] Backup giornaliero (`pg_dump`) **e** della cartella immagini, provato con un ripristino.
- [ ] Nessuna nuova migrazione e' stata aggiunta in questa release.

## 3. Build

```bash
# frontend (il valore di VITE_MAPTILER_KEY entra nel bundle: cambiarla richiede una nuova build)
cd frontend && VITE_MAPTILER_KEY=... npm ci && npm run build      # -> frontend/dist
# facoltativo: VITE_MAP_STYLE=full usa lo stile MapTiler completo invece della cartografia essenziale di LINKR (vedi docs/UX-MAPPA-E-NAVIGAZIONE.md)
# backend (Java 21)
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -DskipTests package   # -> target/backend-0.0.1-SNAPSHOT.jar (~60 MB, senza devtools)
```
`VITE_API_BASE_URL`: lasciare **vuota** con lo stesso dominio. Se l'API fosse su un altro dominio va impostata (REST, immagini e WebSocket la seguono)
e `CORS_ALLOWED_ORIGINS` deve contenere l'origine del sito.

## 4. MapTiler

- [ ] La chiave e' **pubblica** (finisce nel bundle e nelle richieste del browser). Nel pannello MapTiler: restringerla all'origine
      `https://tuodominio.it` (HTTP referrer) e impostare un tetto di richieste, altrimenti chiunque puo' consumare la quota.
- [ ] Controllare che geocoding e mappa funzionino dal dominio vero: se la chiave e' limitata a localhost, entrambi falliranno li'.

## 5. Reverse proxy (Caddy) — esempio, **non eseguito** in questa macchina

```caddyfile
tuodominio.it {
	encode zstd gzip

	handle /api/* {
		reverse_proxy 127.0.0.1:8080          # le immagini hanno gia' le loro intestazioni (CSP sandbox, nosniff): NON aggiungerne qui
	}
	handle /ws {
		reverse_proxy 127.0.0.1:8080          # WebSocket: Caddy lo gestisce senza altre opzioni
	}
	handle {
		root * /srv/linkr/frontend/dist
		@hashed path /assets/*
		header @hashed Cache-Control "public, max-age=31536000, immutable"
		header Strict-Transport-Security "max-age=31536000"
		header X-Content-Type-Options "nosniff"
		header Referrer-Policy "strict-origin-when-cross-origin"
		header Permissions-Policy "camera=(self), microphone=(), geolocation=()"
		header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self' https://api.maptiler.com; worker-src 'self' blob:; child-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
		try_files {path} /index.html          # senza questo un refresh su /events/123 da' 404
		file_server
	}
}
```

La CSP e' stata **provata in Chrome sul bundle di produzione** (mappa, marker, anteprima, Quick Create, dettaglio, crea evento con anteprima
della foto): nessuna violazione. `img-src https:` e' necessario perche' un evento puo' usare l'URL https di un'immagine esterna;
`style-src 'unsafe-inline'` serve a MapLibre e agli stili inline di React. Dopo il deploy riaprire le stesse schermate con la console aperta.

## 6. Avvio del backend (systemd)

```ini
[Service]
User=linkr
EnvironmentFile=/etc/linkr/linkr.env
ExecStart=/usr/lib/jvm/java-21/bin/java -Xmx512m -jar /opt/linkr/backend.jar
Restart=on-failure
```

## 7. Verifiche dopo il deploy (5 minuti)

- [ ] `curl -s https://tuodominio.it/api/health` → `{"status":"UP",...}` (verifica solo che il processo risponda, non il database).
- [ ] `curl -sI https://tuodominio.it/api/events` → `200`, `X-Content-Type-Options`, e `Strict-Transport-Security` se arriva dal proxy.
- [ ] Browser: Discover mostra la mappa; registrazione; login; **refresh della pagina resta loggato**; logout.
- [ ] Crea un evento con una foto scattata col telefono, poi scaricare l'immagine pubblicata e controllare che non abbia EXIF/GPS
      in locale (`exiftool file.jpg`: non caricarla su siti esterni, la pubblicheresti altrove). Aprirla da telefono: stessa rotazione di prima.
- [ ] Due browser: un messaggio di chat compare in tempo reale (WebSocket su `wss://`) e il badge dei non letti si aggiorna.
- [ ] Un POST senza token → `401`; `https://tuodominio.it/api/uploads/event-images/../../etc/passwd` → `400/404`.
- [ ] Riavviare il servizio: le immagini sono ancora li' e il login funziona (il segreto JWT non e' cambiato).

## 8. Limiti da conoscere (accettati per il Capstone)

- **Nessuna protezione dai tentativi ripetuti** su login e registrazione (esiste solo il limite di upload per utente, in memoria).
  Con un sito pubblico aggiungere un limite sul proxy (es. plugin rate-limit di Caddy su `/api/auth/*`) o accettare il rischio.
- Un solo processo: il limite upload e il broker WebSocket sono in memoria; un riavvio li azzera e chi e' connesso si riconnette.
- `/api/health` non controlla il database.
- JWT in `sessionStorage` (leggibile da JavaScript): la CSP sopra e' la difesa principale contro l'XSS.
- Le immagini caricate e mai usate restano su disco (vedi `docs/UPLOAD-IMMAGINI.md`).
