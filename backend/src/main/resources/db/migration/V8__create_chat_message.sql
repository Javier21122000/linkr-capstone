-- Chat privata. La conversazione NON ha una tabella propria: e' la richiesta Link ACCEPTED (link_request),
-- che garantisce gia' "una sola relazione per coppia" e che la chat esista solo tra Link accettati.
-- Migrazione additiva: non tocca le tabelle esistenti.

CREATE TABLE chat_message (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    link_request_id uuid          NOT NULL REFERENCES link_request (id) ON DELETE CASCADE,
    sender_id       uuid          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    body            varchar(2000) NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
    -- Quando il destinatario ha letto il messaggio; NULL = non letto.
    read_at         timestamptz,

    CONSTRAINT chat_message_body_not_blank CHECK (length(btrim(body)) > 0)
);

-- Messaggi di una conversazione, dal piu' recente (paginazione "keyset" su created_at + id).
CREATE INDEX idx_chat_message_conversation ON chat_message (link_request_id, created_at DESC, id DESC);
-- Conteggio dei non letti: solo le righe ancora da leggere.
CREATE INDEX idx_chat_message_unread ON chat_message (link_request_id, sender_id) WHERE read_at IS NULL;
