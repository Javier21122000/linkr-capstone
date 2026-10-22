# Profilo utente: API (Sprint D)

Nessuna migrazione Flyway: `bio` e `avatar_url` esistono già in `app_user` (V2). Tutti gli endpoint richiedono il JWT; l'identità è sempre quella del token.

| Cosa | Endpoint | Note |
|---|---|---|
| Leggere il proprio profilo | `GET /api/users/me` | invariato (`UserResponse`) |
| Modificare nome, bio, avatar | `PUT /api/users/me/profile` | vedi sotto; risponde con `UserResponse` aggiornato |
| Caricare l'avatar | `POST /api/uploads/event-images` (multipart, campo `file`) | **stessa pipeline delle copertine** (tipo reale, 5 MB, metadati rimossi, limite per utente); la risposta contiene `imageUrl` |
| Visibilità in "Scopri persone" | `PUT /api/users/me/discovery` | invariato |
| Interessi | `GET`/`PUT /api/users/me/interests` | invariato (0–15 interessi, sostituzione completa) |
| Profilo di un'altra persona | `GET /api/users/{id}/profile` | `PublicProfileResponse`, vedi privacy |

## `PUT /api/users/me/profile`

```json
{ "displayName": "Anna Rossi", "bio": "Amo la techno.", "avatarUrl": "/api/uploads/event-images/<uuid>.png" }
```

Sostituisce i tre campi (invia sempre tutti e tre; `bio` e `avatarUrl` possono essere `null`).

- `displayName`: obbligatorio, 2–60 caratteri dopo la pulizia (spazi ai bordi tolti, spazi interni ridotti a uno). Niente `<` `>`, caratteri di controllo o invisibili (anche i "bidi override").
- `bio`: facoltativa, massimo 300 caratteri, testo semplice, a capo ammessi (max una riga vuota di seguito). Vuota = `null`.
- `avatarUrl`: `null` per rimuoverlo, altrimenti SOLO il percorso restituito dall'upload e relativo a un file esistente. URL esterni, `http://` e percorsi diversi sono rifiutati. Se il valore non cambia non viene ricontrollato (un account con un avatar preesistente resta modificabile).
- Errori: `400` con `errors` per campo (mai i valori inviati), `401` senza token.
- **Mass assignment**: il DTO ha solo questi tre campi. `id`, `email`, `username`, `role`, `discoverable`, `passwordHash` e qualunque altro campo nel JSON sono ignorati; non si può indicare un altro utente.
- Gli avatar sostituiti non vengono cancellati dal disco (come le copertine degli eventi): resta un file orfano per ogni cambio.

## `GET /api/users/{id}/profile` (privacy)

Un profilo è visibile solo se vale almeno una regola, le stesse del resto di LINKR:

1. **Discover People**: la persona ha l'opt-in `discoverable` **e** anche chi guarda (reciprocità);
2. **Evento**: partecipi a un evento pubblicato e non terminato in cui la persona è visibile agli altri (`visible_to_others`);
3. **Link**: tra voi c'è una richiesta Link accettata;
4. oppure è il tuo profilo.

Altrimenti `404`, identico a un utente inesistente (non si scopre chi ha un account). Se la persona disattiva la visibilità e non ha altre regole valide, sparisce subito.

La risposta contiene solo dati pubblici: `userId`, `username`, `displayName`, `bio`, `avatarUrl`, `interests` (tutti), `sharedInterests` (in comune con te), `linkStatus`, `linkRequestId`. Mai email, ruolo, data di iscrizione, `discoverable` o partecipazioni.

## Frontend

`frontend/src/lib/profile.ts` contiene il client (`updateProfile`, `getPublicProfile`) e i tipi. Il cablaggio nella UI (pannello "Modifica profilo" in `ProfilePage`, pagina profilo pubblico, rotta in `App.tsx`) non è ancora fatto.

## Verifica manuale

1. Accedi, `PUT /api/users/me/profile` con nome e bio: `GET /api/users/me` li riflette; `role`, `email`, `id` invariati anche se li includi nel JSON.
2. `POST /api/uploads/event-images` con un'immagine, poi `PUT` con `avatarUrl` = `imageUrl`; con `null` lo rimuovi.
3. Con due account: `GET /api/users/{id}/profile` dà `404` finché non vale una regola di privacy; attiva `PUT /api/users/me/discovery` su entrambi e diventa `200`.
