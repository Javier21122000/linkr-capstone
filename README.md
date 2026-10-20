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

## 📌 Status

Under development — October 2026.
