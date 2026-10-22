-- Origine esterna degli eventi (per ora solo Ticketmaster). Migrazione STRETTAMENTE ADDITIVA: crea una tabella nuova e
-- non tocca, non legge e non modifica nessuna riga di event, event_participation, app_user o delle altre tabelle.
--
-- Una riga collega un evento di LINKR all'annuncio originale su una fonte esterna. Serve a:
--  - non importare due volte lo stesso annuncio (UNIQUE (source, external_id)) e legare un evento a una sola origine (UNIQUE (event_id));
--  - mostrare la provenienza e il link all'annuncio originale (attribuzione);
--  - ricordare l'ultimo stato visto sulla fonte (es. annullato/rinviato) senza riattivare in automatico eventi annullati.
-- Nello sprint C2/fase 1 la tabella resta VUOTA: il dry-run non scrive nulla (l'importazione persistente e' uno sprint successivo).

CREATE TABLE event_source (
    id             uuid          PRIMARY KEY DEFAULT uuidv7(),
    event_id       uuid          NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    source         varchar(30)   NOT NULL,
    -- Identificativo dell'annuncio sulla fonte (per Ticketmaster: l'id dell'evento nella Discovery API).
    external_id    varchar(100)  NOT NULL,
    -- Link all'annuncio originale: solo per attribuzione, LINKR non vende biglietti.
    source_url     varchar(1000),
    -- Ultimo stato visto sulla fonte (es. onsale, canceled, postponed), in minuscolo come lo restituisce la fonte.
    source_status  varchar(30),
    -- Ultima volta in cui l'annuncio e' comparso nei risultati della fonte; NULL = mai visto da un import.
    last_seen_at   timestamptz,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT event_source_source_check CHECK (source IN ('TICKETMASTER')),
    CONSTRAINT event_source_external_id_not_blank CHECK (length(btrim(external_id)) > 0),
    -- Lo stesso annuncio esterno corrisponde a un solo evento di LINKR, garantito dal database anche con import concorrenti.
    CONSTRAINT event_source_source_external_id_key UNIQUE (source, external_id),
    -- Relazione 1:1 con event: un evento ha al massimo un'origine esterna. L'indice che nasce da questo vincolo copre anche la ricerca
    -- dall'evento alla sua origine (dettaglio evento) e la pulizia a cascata, quindi non serve un indice separato su event_id.
    CONSTRAINT event_source_event_id_key UNIQUE (event_id)
);
