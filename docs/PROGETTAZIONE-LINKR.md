# PROGETTAZIONE — LINKR CAPSTONE

## DESCRIZIONE

LINKR è una web application social mobile-first
dedicata alla scoperta di eventi e alla creazione
di nuove connessioni tra persone.

L'applicazione permette di scoprire eventi a Milano,
visualizzarli su una mappa, partecipare e conoscere
altri utenti interessati alle stesse esperienze.

Concept:

"Discover what's happening.
Find your people.
Go together."

La versione Capstone rappresenta un MVP didattico
della futura piattaforma LINKR.

---

# STACK TECNOLOGICO

## Backend

- Java 21
- Maven
- Spring Boot 4.1.1

Dipendenze principali:

- Spring Web MVC
- Spring Data JPA
- PostgreSQL Driver
- Lombok
- Spring Boot DevTools
- Spring Validation
- Spring Security (previsto)
- Spring Security BCrypt (previsto)
- Spring Security JWT (previsto)
- Flyway
- Spring Boot Test

## Frontend

- React 19
- TypeScript
- Vite
- React Router
- Axios
- Tailwind CSS
- Lucide React

## Database

- PostgreSQL 18
- pgAdmin / Postgres.app
- Flyway per le migrazioni

## Strumenti

- IntelliJ IDEA
- Visual Studio Code
- Git
- GitHub
- Postman o Bruno
- DrawSQL
- Trello

---

# ARCHITETTURA

Architettura Full Stack a tre livelli.

FRONTEND
React + TypeScript

        |
        | HTTP / REST / JSON
        v

BACKEND
Spring Boot + Spring Security

        |
        | Spring Data JPA
        v

DATABASE
PostgreSQL

Il frontend comunica con il backend tramite Axios.

Il backend espone API REST e gestisce:
- Validazione
- Autenticazione
- Autorizzazione
- Business logic
- Persistenza

Le entità JPA non vengono esposte direttamente.

Si utilizzano DTO di input e output.

---

# ENTITÀ

## USER

Rappresenta un utente registrato.

Campi previsti:

- id: UUID
- username: String (unique)
- email: String (unique)
- passwordHash: String
- displayName: String
- bio: String (nullable)
- avatarUrl: String (nullable)
- role: Enum USER / ADMIN
- createdAt: Instant
- updatedAt: Instant

Funzionalità:

- Registrazione
- Login
- Profilo personale
- Modifica profilo
- Visualizzazione profili pubblici

La password viene salvata tramite BCrypt.

Il ruolo viene assegnato dal server.

Non è possibile scegliere ADMIN durante la registrazione.

---

## EVENT

Rappresenta un evento.

Entità già implementata.

Campi:

- id: UUID
- title: String
- description: String
- category: EventCategory
- status: EventStatus
- startsAt: Instant
- endsAt: Instant (nullable)
- venueName: String
- address: String
- neighbourhood: String
- latitude: double
- longitude: double
- imageUrl: String (nullable)
- imageAlt: String (nullable)
- imageCredit: String (nullable)
- demo: boolean
- createdAt: Instant
- updatedAt: Instant

Categorie:

- MUSIC
- FOOD
- CULTURE
- OUTDOORS
- NIGHTLIFE
- SPORT
- SOCIAL

Stati:

- DRAFT
- PUBLISHED
- CANCELLED

Funzionalità:

- Lista eventi
- Dettaglio evento
- Filtri per categoria
- Filtri per data
- Visualizzazione geografica
- Creazione e modifica eventi (previste)

Solo gli eventi pubblicati sono visibili pubblicamente.

---

## EVENT_PARTICIPATION

Rappresenta la partecipazione di un utente a un evento.

Campi previsti:

- id: UUID
- userId: FK -> USER
- eventId: FK -> EVENT
- status: Enum GOING / CANCELLED
- createdAt: Instant

Vincolo:

UNIQUE(userId, eventId)

Funzionalità:

- Partecipa a un evento
- Annulla partecipazione
- Visualizza partecipanti
- Visualizza eventi a cui partecipa

Un utente non può partecipare due volte
allo stesso evento.

---

## LINK_REQUEST

Rappresenta una richiesta di connessione.

Campi previsti:

- id: UUID
- senderId: FK -> USER
- recipientId: FK -> USER
- eventId: FK -> EVENT (nullable)
- status: Enum PENDING / ACCEPTED / REJECTED
- createdAt: Instant
- respondedAt: Instant (nullable)

Funzionalità:

- Invia richiesta Link
- Accetta richiesta
- Rifiuta richiesta
- Visualizza richieste ricevute
- Visualizza connessioni accettate

Regole:

- Non è possibile inviare Link a sé stessi.
- Solo il destinatario può rispondere.
- Non devono esistere richieste duplicate attive.
- I controlli vengono effettuati nel backend.

---

# CONTROLLER ED ENDPOINT

## AUTH

POST /api/auth/register

Registra un utente tramite DTO.

POST /api/auth/login

Verifica le credenziali e restituisce un JWT.

Il token contiene soltanto i dati necessari.

---

## USER

GET /api/users/me

Restituisce il profilo dell'utente autenticato.

PATCH /api/users/me

Modifica i campi consentiti del proprio profilo.

GET /api/users/{id}

Restituisce il profilo pubblico di un utente.

---

## EVENT

GET /api/events

Lista pubblica degli eventi.

Supporta:
- category
- from
- to

GET /api/events/{id}

Dettaglio di un evento pubblicato.

POST /api/events

Creazione evento, con autorizzazioni da definire.

PATCH /api/events/{id}

Modifica evento, con controllo proprietario o ruolo.

DELETE /api/events/{id}

Eliminazione o annullamento evento secondo
le regole applicative.

---

## PARTICIPATION

POST /api/events/{id}/participations

Partecipa a un evento.

DELETE /api/events/{id}/participations/me

Annulla la propria partecipazione.

GET /api/events/{id}/participants

Visualizza i partecipanti, rispettando
le impostazioni di visibilità.

GET /api/users/me/events

Visualizza gli eventi dell'utente autenticato.

---

## LINK REQUEST

POST /api/links

Invia una richiesta di connessione.

GET /api/links/received

Visualizza le richieste ricevute.

GET /api/links/sent

Visualizza le richieste inviate.

PATCH /api/links/{id}/accept

Accetta una richiesta.

PATCH /api/links/{id}/reject

Rifiuta una richiesta.

GET /api/links/connections

Visualizza le connessioni accettate.

---

# SICUREZZA

- Spring Security
- Password BCrypt
- Autenticazione JWT
- Ruoli USER / ADMIN
- Validazione DTO
- Controllo proprietario delle risorse
- CORS configurabile
- Gestione centralizzata degli errori
- Credenziali tramite variabili d'ambiente

Le password non vengono restituite dalle API.

I dati sensibili non vengono inseriti nei log.

Il backend non si fida degli identificativi utente
inviati dal frontend.

L'identità viene ricavata dal token autenticato.

---

# DATABASE

Database PostgreSQL: linkr_db

Migrazioni gestite tramite Flyway.

Hibernate:

ddl-auto=validate

Le modifiche allo schema vengono effettuate
tramite migrazioni versionate.

Il database è protetto da un controllo
sul nome prima dell'esecuzione di Flyway.

---

# FRONTEND

Pagine previste:

- Discover
- Dettaglio evento
- Mappa eventi
- Login
- Registrazione
- Profilo personale
- Profilo pubblico
- Eventi a cui partecipo
- Richieste Link

Componenti:

- Navbar
- EventCard
- EventFilters
- EventMap
- EventDetails
- UserAvatar
- UserProfile
- LinkButton
- LoadingState
- ErrorState

L'interfaccia sarà responsive e mobile-first.

---

# TESTING

Backend:

- Unit test
- Controller test
- Integration test
- Verifica validazione DTO
- Verifica autorizzazioni
- Verifica migrazioni database

Frontend:

- TypeScript typecheck
- Production build
- Test responsive
- Test comunicazione API
- Test loading/error/empty state

---

# STATO DEL PROGETTO

COMPLETATO:

- Setup repository
- Backend Spring Boot
- Frontend React
- PostgreSQL
- Flyway
- Configurazione CORS
- Modulo Event
- API eventi
- Discover con dati API
- Dettaglio evento
- Protezione database

DA IMPLEMENTARE:

- Autenticazione
- Profili utente
- Partecipazioni
- Richieste Link
- Mappa interattiva
- CRUD eventi protetto
- Deploy
- Documentazione finale

---

# UTILIZZO AI

Claude:
- Progettazione e sviluppo backend
- Analisi delle entità
- Implementazione servizi e repository
- Test backend

Codex:
- Sviluppo frontend
- Componenti React
- Integrazione API
- Responsive design

GitHub Copilot:
- Revisione modifiche
- Controlli Git
- Preparazione commit

ChatGPT:
- Coordinamento tecnico
- Analisi architetturale
- Definizione requisiti
- Revisione delle decisioni progettuali

Le modifiche vengono verificate tramite test,
controlli manuali e revisioni prima dei commit.