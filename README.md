# 🔗 LINKR

### Discover what's happening. Find your people. Go together.

Linkr is a mobile-first social event discovery platform developed as a Full Stack Capstone Project at EPICODE.

## 🌍 Concept

Linkr connects people through real-world events and shared interests.

CITY → EVENTS → PEOPLE → LINKS → REAL-LIFE EXPERIENCES

## 🚀 Core Features

- User authentication
- User profiles and interests
- Geolocated event discovery
- Interactive map
- Event participation
- People discovery
- Shared interests
- Link requests and connections

## 🛠 Tech Stack

**Frontend**
- React
- TypeScript
- Vite
- Tailwind CSS
- MapLibre GL JS with MapTiler

**Backend**
- Java
- Spring Boot
- Spring Security
- Spring Data JPA

**Database**
- PostgreSQL

## 📁 Project Structure

- `frontend/` — React application
- `backend/` — Spring Boot REST API
- `docs/` — Technical documentation

## Environment

Use a single real `.env` file in the repository root. Vite reads it through
`envDir: ".."`; configure `VITE_MAPTILER_KEY` there for the Discover basemap.
Spring Boot does not load this file through Vite or `envDir`: export the
`LINKR_DB_*` and `LINKR_JWT_SECRET` variables before starting the backend. From
the repository root, load them in the current shell before running Spring Boot:

```bash
set -a
source .env
set +a
```

Real `.env` files are ignored by Git and must never be committed.

## Test

```bash
cd frontend
npm run typecheck   # TypeScript
npm test            # node:test, nessuna dipendenza: fuso di Milano, ora legale, luoghi MapTiler, navigazione (Node ≥ 22.18)
npm run build
```

UX, mappa, fuso orario e ambiente delle pagine: [`docs/UX-MAPPA-E-NAVIGAZIONE.md`](docs/UX-MAPPA-E-NAVIGAZIONE.md).
Cosa manca al backend per modificare il profilo: [`docs/PROFILO-API.md`](docs/PROFILO-API.md).

## Deploy

Checklist ordinata (variabili, database, build, reverse proxy con HTTPS e CSP, verifiche dopo la pubblicazione): [`docs/DEPLOY.md`](docs/DEPLOY.md).
Upload e rimozione dei metadati delle immagini: [`docs/UPLOAD-IMMAGINI.md`](docs/UPLOAD-IMMAGINI.md).
Integrazione Ticketmaster (spenta di base; dry-run e importazione manuale riservati agli admin, ognuno con il suo interruttore): [`docs/TICKETMASTER.md`](docs/TICKETMASTER.md).

## Session security

The frontend keeps the authenticated session in `sessionStorage`, so a valid JWT survives page refreshes but is removed
when the browser session ends or the user logs out. Expired sessions are discarded and no password or refresh token is
stored. Because `sessionStorage` is readable by JavaScript, a successful XSS attack could still steal the JWT; preventing
script injection and maintaining an appropriate Content Security Policy remain required protections. A Content Security Policy
tested against the production bundle is in [`docs/DEPLOY.md`](docs/DEPLOY.md).

## 📌 Status

Under development — October 2026.
