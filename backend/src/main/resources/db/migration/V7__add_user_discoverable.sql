-- Discover People: opt-in esplicito. Di default nessuno e' visibile (false); l'utente lo attiva dal profilo.
-- Migrazione additiva: le righe esistenti ricevono false.

ALTER TABLE app_user ADD COLUMN discoverable boolean NOT NULL DEFAULT false;

-- La discovery cerca solo tra gli utenti visibili: indice parziale, piccolo anche con molti utenti.
CREATE INDEX idx_app_user_discoverable ON app_user (id) WHERE discoverable;
