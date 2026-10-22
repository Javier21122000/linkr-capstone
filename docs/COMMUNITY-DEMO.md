# Community demo

Ambiente dimostrativo sociale: **15 personaggi inventati, 36 uscite, 187 partecipazioni, 8 Link accettati, 8 messaggi**. Tutto è inventato e marcato DEMO. Nessuna migrazione Flyway.

## Come attivarla (sviluppo)

Servono **entrambe** le condizioni, altrimenti non viene nemmeno creato il bean:

1. profilo Spring `dev` (`SPRING_PROFILES_ACTIVE=dev`);
2. `LINKR_DEMO_COMMUNITY_ENABLED=true` (proprietà `linkr.demo.community.enabled`, `false` di default).

Parte all'avvio dell'applicazione e scrive nel database a cui l'app è collegata (`LINKR_DB_URL`). Per spegnerla basta togliere la variabile: i dati già creati restano.
In produzione il profilo `dev` non va impostato (vedi `DEPLOY.md`): il seed non può partire.

## Come si riconoscono gli account demo

- id fissi `018f4000-0000-7000-8000-0000000000NN`;
- username `demo_<nome>` (es. `demo_giulia`), email `demo_<nome>@demo.linkr.invalid` (dominio riservato `.invalid`: non esiste);
- la bio termina con «Profilo demo.»;
- ruolo sempre `USER` (mai `ADMIN`); avatar fotografico locale `/demo/avatars/<nome>.webp`;
- **non possono accedere**: `password_hash` non è un hash valido, quindi nessuna password esiste né nel repository né nel database;
- visibili in Discover People (`discoverable = true`), con 4 interessi ciascuno.

Gli eventi sono `demo = true`, `event_type = OFFICIAL` (il vincolo `event_community_not_demo` vieta demo + COMMUNITY), `created_by` = un personaggio demo, 2–3 per personaggio, nei prossimi 14 giorni, in luoghi **pubblici** di Milano (piazze, parchi, vie: nessun locale o organizzatore reale). La descrizione termina con «Incontro dimostrativo inventato: non è un evento reale.» Id fissi `018f3000-…`.

## Illustrazioni (copertine e avatar)

Le copertine sono SVG originali, disegnate da `scripts/generate-demo-art.py` (nessun asset di terzi per le copertine, **nessuna licenza o attribuzione da gestire**; `image_credit` = «Illustrazione originale LINKR»):
- `frontend/public/demo/covers/<tema>.svg`: 22 copertine 16:9 (musica, cibo, cultura, all'aperto, vita notturna, sport, socialità), una per tema, scelta per ogni evento in `DemoCatalog.COVER_THEME`. Riguardano sia le 36 uscite community sia i 12 eventi demo storici (`DemoEventSeeder`), che prima usavano URL Unsplash esterni;
- `frontend/public/demo/avatars/<nome>.webp`: 15 avatar fotografici 302×302 (WebP q90), ritagliati da `scripts/griglia-profili-1.jpeg` (griglia 5×3). Non sono generati dallo script: per cambiarli, sostituire i file mantenendo i nomi.

`imageUrl`/`avatarUrl` sono percorsi relativi al sito (stessa origine, compatibili con la CSP `img-src 'self'`): fanno parte della build del frontend e restano disponibili dopo ogni riavvio. Il seed li applica anche a righe demo già presenti (solo nostre, con `IS DISTINCT FROM`: nessun duplicato, nessun altro dato toccato). Per rigenerare: `python3 scripts/generate-demo-art.py`.

## Isolamento e idempotenza

- Una sola transazione, **mai DELETE**.
- Prima di scrivere controlla che nessun account/evento NON demo occupi gli id, gli username o le email necessari: in quel caso si ferma **senza scrivere nulla** (avviso nel log).
- Gli `UPDATE` riguardano solo righe demo riconosciute (email demo, oppure `demo = true` con creatore demo): mai account reali, mai eventi Ticketmaster o creati da utenti, mai partecipazioni o Link non creati dal seed.
- Rieseguirlo non cambia nulla (id fissi, `ON CONFLICT`). Un evento demo già terminato viene spostato nel futuro, con le sue partecipazioni.
- Partecipazioni (`GOING`/`INTERESTED`, visibili agli altri) solo tra account demo, scelte per compatibilità di interessi; i Link accettati e i messaggi sono solo tra account demo. Una persona reale può vedere i personaggi in Discover People e partecipare ai loro eventi, ma loro non rispondono.

## Rimozione

Non c'è un comando di rimozione (nessuna operazione distruttiva automatica). Se servisse, a mano: gli utenti `email LIKE '%@demo.linkr.invalid'` si eliminano a cascata con interessi, partecipazioni, Link e messaggi; gli eventi demo restano senza creatore (`created_by` diventa `NULL`) e vanno eliminati separatamente con `id::text LIKE '018f3000-%'`.

## Codice

`backend/src/main/java/com/linkr/backend/demo/`: `DemoCatalog` (dati), `DemoPersona`, `CommunityDemoSeeder`, `CommunityDemoRunner`. Test: `CommunityDemoSeederTest` (solo `linkr_test_db`, rollback).
