-- Eventi creati dagli utenti. Migrazione additiva: non tocca ne' cancella i dati esistenti.
--  - event_type: OFFICIAL (curato da LINKR, inclusi gli eventi demo) oppure COMMUNITY (creato da un utente).
--    Le righe esistenti ricevono OFFICIAL grazie al DEFAULT.
--  - created_by: chi ha creato l'evento. NULL per gli eventi demo/ufficiali storici e se l'utente viene eliminato
--    (l'evento e le partecipazioni degli altri restano, ma solo un amministratore potra' gestirlo).

ALTER TABLE event ADD COLUMN event_type varchar(20) NOT NULL DEFAULT 'OFFICIAL';
ALTER TABLE event ADD COLUMN created_by uuid REFERENCES app_user (id) ON DELETE SET NULL;

ALTER TABLE event ADD CONSTRAINT event_type_check CHECK (event_type IN ('OFFICIAL', 'COMMUNITY'));
-- Un evento dimostrativo non puo' essere creato da un utente.
ALTER TABLE event ADD CONSTRAINT event_community_not_demo CHECK (NOT (demo AND event_type = 'COMMUNITY'));

-- "I miei eventi" e conteggio anti-spam delle creazioni per utente.
CREATE INDEX idx_event_created_by ON event (created_by, created_at DESC);
