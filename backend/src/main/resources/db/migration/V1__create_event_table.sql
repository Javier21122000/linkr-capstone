-- Tabella degli eventi di LINKR.
-- Le date sono timestamptz (salvate in UTC); la conversione in Europe/Rome avviene nelle risposte API.

CREATE TABLE event (
    id            uuid             PRIMARY KEY DEFAULT uuidv7(),
    title         varchar(120)     NOT NULL,
    description   text             NOT NULL,
    category      varchar(30)      NOT NULL,
    status        varchar(20)      NOT NULL DEFAULT 'PUBLISHED',
    starts_at     timestamptz      NOT NULL,
    ends_at       timestamptz,
    venue_name    varchar(120)     NOT NULL,
    address       varchar(200)     NOT NULL,
    neighbourhood varchar(60)      NOT NULL,
    latitude      double precision NOT NULL,
    longitude     double precision NOT NULL,
    image_url     varchar(500),
    image_alt     varchar(200),
    image_credit  varchar(120),
    demo          boolean          NOT NULL DEFAULT false,
    created_at    timestamptz      NOT NULL DEFAULT now(),
    updated_at    timestamptz      NOT NULL DEFAULT now(),

    CONSTRAINT event_category_check  CHECK (category IN ('MUSIC', 'FOOD', 'CULTURE', 'OUTDOORS', 'NIGHTLIFE', 'SPORT', 'SOCIAL')),
    CONSTRAINT event_status_check    CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT event_latitude_check  CHECK (latitude  BETWEEN -90  AND 90),
    CONSTRAINT event_longitude_check CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT event_dates_check     CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX idx_event_status_starts_at ON event (status, starts_at);
CREATE INDEX idx_event_category ON event (category);
