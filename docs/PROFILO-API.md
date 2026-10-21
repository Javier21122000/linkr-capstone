# Profilo: cosa il backend supporta e cosa manca

Stato verificato leggendo i controller (nessuna modifica al backend, al database o alle migrazioni).

## Già supportato

| Cosa | Endpoint | Note |
|---|---|---|
| Leggere il proprio profilo | `GET /api/users/me` | `displayName`, `bio`, `avatarUrl`, `discoverable`, … |
| Visibilità in "Scopri persone" | `PUT /api/users/me/discovery` | la UI lo usa nel Profilo |
| Interessi | `GET`/`PUT /api/users/me/interests` | la UI li modifica in `/profile/interests` |

## Non supportato (quindi la UI non lo finge)

- **Nome visualizzato**: viene scelto alla registrazione (`RegisterRequest.displayName`, max 60 caratteri) e non esiste un endpoint per cambiarlo.
- **Bio / informazioni personali non sensibili**: `UserResponse.bio` esiste ma non c'è modo di scriverla.
- **Foto profilo**: `UserResponse.avatarUrl` esiste ma non c'è upload. `POST /api/uploads/event-images` serve solo alle copertine degli eventi.

Nel Profilo non ci sono quindi pulsanti "Modifica nome" o "Cambia foto": non salverebbero nulla.

## Proposta di intervento backend (piccolo e separato)

1. `PUT /api/users/me` con `{ "displayName": "…", "bio": "…" }`
   - `displayName`: obbligatorio, `trim`, 1–60 caratteri (le stesse regole della registrazione).
   - `bio`: facoltativa, massimo 280 caratteri, testo semplice (la UI la mostra senza HTML).
   - Risposta: `UserResponse` aggiornato. Il frontend aggiorna la sessione senza ricaricare.
2. `POST /api/users/me/avatar` (multipart, un file) e `DELETE /api/users/me/avatar`
   - Riusare la pipeline di `EventImageService` (controllo del tipo, dimensioni, rimozione dei metadati EXIF/GPS con `ImageMetadataSanitizer`).
   - Salvare fuori dalla copia di lavoro di Git, come per le immagini degli eventi (`LINKR_UPLOAD_DIR`).
3. Nessuna migrazione Flyway dovrebbe servire se le colonne `bio` e `avatar_url` esistono già (la risposta di `GET /api/users/me` le espone): **verificarlo prima**, e se servisse una migrazione fermare Spring Boot come da procedura.
4. Test: validazione dei limiti, un utente non può modificare un altro, i metadati dell'avatar vengono rimossi.

Quando questi endpoint esisteranno, il Profilo può ricevere un pannello "Modifica profilo" con stato di salvataggio ed errori di campo, come `CreateEventPage`.
