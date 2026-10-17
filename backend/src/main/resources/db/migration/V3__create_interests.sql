-- Interessi di LINKR: catalogo curato (categorie + interessi specifici) e selezione degli utenti.
-- Le categorie servono solo a organizzare la UI; per il matching si usano gli interessi specifici.
-- Gli UUID del catalogo sono FISSI (scritti qui a mano): restano uguali in ogni database e ambiente.

CREATE TABLE interest_category (
    id         uuid        PRIMARY KEY,
    slug       varchar(50) NOT NULL,
    name       varchar(60) NOT NULL,
    sort_order integer     NOT NULL,

    CONSTRAINT interest_category_slug_key UNIQUE (slug)
);

CREATE TABLE interest (
    id          uuid        PRIMARY KEY,
    category_id uuid        NOT NULL REFERENCES interest_category (id),
    slug        varchar(50) NOT NULL,
    name        varchar(60) NOT NULL,
    sort_order  integer     NOT NULL,

    CONSTRAINT interest_slug_key UNIQUE (slug),
    CONSTRAINT interest_category_sort_key UNIQUE (category_id, sort_order)
);

-- Indice per caricare gli interessi di una categoria / il catalogo ordinato.
CREATE INDEX idx_interest_category ON interest (category_id, sort_order);

CREATE TABLE user_interest (
    user_id     uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    interest_id uuid NOT NULL REFERENCES interest (id) ON DELETE CASCADE,

    PRIMARY KEY (user_id, interest_id)
);

-- La chiave primaria copre le ricerche per utente; questo indice serve a trovare gli utenti di un interesse (matching futuro).
CREATE INDEX idx_user_interest_interest ON user_interest (interest_id);

-- ---------- Catalogo iniziale ----------

INSERT INTO interest_category (id, slug, name, sort_order) VALUES ('018f1000-0000-7000-8000-000000000001', 'music', 'Musica', 1);
INSERT INTO interest (id, category_id, slug, name, sort_order) VALUES
    ('018f2000-0000-7000-8000-000000000001', '018f1000-0000-7000-8000-000000000001', 'techno', 'Techno', 1),
    ('018f2000-0000-7000-8000-000000000002', '018f1000-0000-7000-8000-000000000001', 'house', 'House', 2),
    ('018f2000-0000-7000-8000-000000000003', '018f1000-0000-7000-8000-000000000001', 'uk-garage', 'UK Garage', 3),
    ('018f2000-0000-7000-8000-000000000004', '018f1000-0000-7000-8000-000000000001', 'hip-hop', 'Hip-Hop', 4),
    ('018f2000-0000-7000-8000-000000000005', '018f1000-0000-7000-8000-000000000001', 'jazz', 'Jazz', 5),
    ('018f2000-0000-7000-8000-000000000006', '018f1000-0000-7000-8000-000000000001', 'concerts', 'Concerti', 6),
    ('018f2000-0000-7000-8000-000000000007', '018f1000-0000-7000-8000-000000000001', 'dj-set', 'DJ set', 7),
    ('018f2000-0000-7000-8000-000000000008', '018f1000-0000-7000-8000-000000000001', 'live-music', 'Musica dal vivo', 8);

INSERT INTO interest_category (id, slug, name, sort_order) VALUES ('018f1000-0000-7000-8000-000000000002', 'sport', 'Sport', 2);
INSERT INTO interest (id, category_id, slug, name, sort_order) VALUES
    ('018f2000-0000-7000-8000-000000000009', '018f1000-0000-7000-8000-000000000002', 'football', 'Calcio', 1),
    ('018f2000-0000-7000-8000-000000000010', '018f1000-0000-7000-8000-000000000002', 'basketball', 'Basket', 2),
    ('018f2000-0000-7000-8000-000000000011', '018f1000-0000-7000-8000-000000000002', 'running', 'Running', 3),
    ('018f2000-0000-7000-8000-000000000012', '018f1000-0000-7000-8000-000000000002', 'calisthenics', 'Calisthenics', 4),
    ('018f2000-0000-7000-8000-000000000013', '018f1000-0000-7000-8000-000000000002', 'gym', 'Palestra', 5),
    ('018f2000-0000-7000-8000-000000000014', '018f1000-0000-7000-8000-000000000002', 'climbing', 'Arrampicata', 6),
    ('018f2000-0000-7000-8000-000000000015', '018f1000-0000-7000-8000-000000000002', 'cycling', 'Ciclismo', 7);

INSERT INTO interest_category (id, slug, name, sort_order) VALUES ('018f1000-0000-7000-8000-000000000003', 'art-culture', 'Arte e cultura', 3);
INSERT INTO interest (id, category_id, slug, name, sort_order) VALUES
    ('018f2000-0000-7000-8000-000000000016', '018f1000-0000-7000-8000-000000000003', 'photography', 'Fotografia', 1),
    ('018f2000-0000-7000-8000-000000000017', '018f1000-0000-7000-8000-000000000003', 'cinema', 'Cinema', 2),
    ('018f2000-0000-7000-8000-000000000018', '018f1000-0000-7000-8000-000000000003', 'museums', 'Musei', 3),
    ('018f2000-0000-7000-8000-000000000019', '018f1000-0000-7000-8000-000000000003', 'contemporary-art', 'Arte contemporanea', 4),
    ('018f2000-0000-7000-8000-000000000020', '018f1000-0000-7000-8000-000000000003', 'theatre', 'Teatro', 5),
    ('018f2000-0000-7000-8000-000000000021', '018f1000-0000-7000-8000-000000000003', 'design', 'Design', 6);

INSERT INTO interest_category (id, slug, name, sort_order) VALUES ('018f1000-0000-7000-8000-000000000004', 'nightlife', 'Nightlife', 4);
INSERT INTO interest (id, category_id, slug, name, sort_order) VALUES
    ('018f2000-0000-7000-8000-000000000022', '018f1000-0000-7000-8000-000000000004', 'clubbing', 'Clubbing', 1),
    ('018f2000-0000-7000-8000-000000000023', '018f1000-0000-7000-8000-000000000004', 'cocktail-bar', 'Cocktail bar', 2),
    ('018f2000-0000-7000-8000-000000000024', '018f1000-0000-7000-8000-000000000004', 'festivals', 'Festival', 3),
    ('018f2000-0000-7000-8000-000000000025', '018f1000-0000-7000-8000-000000000004', 'underground-nights', 'Serate underground', 4),
    ('018f2000-0000-7000-8000-000000000026', '018f1000-0000-7000-8000-000000000004', 'karaoke', 'Karaoke', 5),
    ('018f2000-0000-7000-8000-000000000027', '018f1000-0000-7000-8000-000000000004', 'themed-nights', 'Serate a tema', 6);

INSERT INTO interest_category (id, slug, name, sort_order) VALUES ('018f1000-0000-7000-8000-000000000005', 'food', 'Food', 5);
INSERT INTO interest (id, category_id, slug, name, sort_order) VALUES
    ('018f2000-0000-7000-8000-000000000028', '018f1000-0000-7000-8000-000000000005', 'street-food', 'Street food', 1),
    ('018f2000-0000-7000-8000-000000000029', '018f1000-0000-7000-8000-000000000005', 'asian-cuisine', 'Cucina asiatica', 2),
    ('018f2000-0000-7000-8000-000000000030', '018f1000-0000-7000-8000-000000000005', 'brunch', 'Brunch', 3),
    ('018f2000-0000-7000-8000-000000000031', '018f1000-0000-7000-8000-000000000005', 'restaurants', 'Ristoranti', 4),
    ('018f2000-0000-7000-8000-000000000032', '018f1000-0000-7000-8000-000000000005', 'aperitivo', 'Aperitivi', 5),
    ('018f2000-0000-7000-8000-000000000033', '018f1000-0000-7000-8000-000000000005', 'tastings', 'Degustazioni', 6);

INSERT INTO interest_category (id, slug, name, sort_order) VALUES ('018f1000-0000-7000-8000-000000000006', 'lifestyle', 'Lifestyle', 6);
INSERT INTO interest (id, category_id, slug, name, sort_order) VALUES
    ('018f2000-0000-7000-8000-000000000034', '018f1000-0000-7000-8000-000000000006', 'travel', 'Viaggi', 1),
    ('018f2000-0000-7000-8000-000000000035', '018f1000-0000-7000-8000-000000000006', 'languages', 'Lingue', 2),
    ('018f2000-0000-7000-8000-000000000036', '018f1000-0000-7000-8000-000000000006', 'gaming', 'Gaming', 3),
    ('018f2000-0000-7000-8000-000000000037', '018f1000-0000-7000-8000-000000000006', 'technology', 'Tecnologia', 4),
    ('018f2000-0000-7000-8000-000000000038', '018f1000-0000-7000-8000-000000000006', 'fashion', 'Moda', 5),
    ('018f2000-0000-7000-8000-000000000039', '018f1000-0000-7000-8000-000000000006', 'hiking', 'Escursionismo', 6),
    ('018f2000-0000-7000-8000-000000000040', '018f1000-0000-7000-8000-000000000006', 'volunteering', 'Volontariato', 7);
