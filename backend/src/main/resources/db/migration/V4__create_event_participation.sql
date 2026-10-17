-- Partecipazioni agli eventi: un'INTENZIONE dichiarata dall'utente (INTERESTED / GOING), non una presenza verificata.
-- Migrazione additiva: non tocca le tabelle esistenti.

CREATE TABLE event_participation (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id           uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    event_id          uuid        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    status            varchar(20) NOT NULL,
    -- Visibilita' agli altri partecipanti: indipendente dallo stato e SEMPRE falsa se non scelta esplicitamente.
    visible_to_others boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),

    -- Al massimo una partecipazione per utente ed evento, garantito dal database anche con richieste concorrenti.
    -- Questo indice copre anche le ricerche "partecipazioni di un utente" (user_id e' la prima colonna).
    CONSTRAINT event_participation_user_event_key UNIQUE (user_id, event_id),
    CONSTRAINT event_participation_status_check   CHECK (status IN ('INTERESTED', 'GOING'))
);

-- Partecipazioni di un evento (conteggi e, in futuro, elenco dei partecipanti visibili).
CREATE INDEX idx_event_participation_event ON event_participation (event_id, status);
