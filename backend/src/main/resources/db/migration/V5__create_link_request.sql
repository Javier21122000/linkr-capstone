-- Richieste Link: collegamento tra due persone, nato da un evento a cui partecipano entrambe.
-- L'evento e' solo il CONTESTO: il Link e' una relazione persistente, indipendente da evento e partecipazioni.
-- Migrazione additiva: non tocca le tabelle esistenti.

CREATE TABLE link_request (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    requester_id uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    recipient_id uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- Se l'evento viene cancellato il Link resta, solo senza contesto.
    event_id     uuid        REFERENCES event (id) ON DELETE SET NULL,
    status       varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    responded_at timestamptz,

    CONSTRAINT link_request_status_check    CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    CONSTRAINT link_request_not_self        CHECK (requester_id <> recipient_id),
    -- Una richiesta ha una data di risposta se e solo se non e' piu' in attesa.
    CONSTRAINT link_request_responded_check CHECK ((status = 'PENDING') = (responded_at IS NULL))
);

-- UNA sola richiesta per coppia di utenti, in qualunque direzione e per qualunque evento (A->B blocca anche B->A).
-- LEAST/GREATEST rendono la coppia "non ordinata". E' il database a garantirlo, anche con richieste simultanee.
CREATE UNIQUE INDEX uq_link_request_pair
    ON link_request (LEAST(requester_id, recipient_id), GREATEST(requester_id, recipient_id));

-- Richieste inviate / ricevute (elenchi ordinati per data) e conteggi anti-spam per mittente.
CREATE INDEX idx_link_request_requester ON link_request (requester_id, created_at DESC);
CREATE INDEX idx_link_request_recipient ON link_request (recipient_id, created_at DESC);
