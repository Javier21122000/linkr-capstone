-- Utenti di LINKR. Il nome "app_user" evita la parola riservata "user" di PostgreSQL.
-- Gli interessi, le partecipazioni e le richieste Link arriveranno con migrazioni successive.

CREATE TABLE app_user (
    id            uuid         PRIMARY KEY DEFAULT uuidv7(),
    username      varchar(30)  NOT NULL,
    email         varchar(254) NOT NULL,
    password_hash varchar(100) NOT NULL,
    display_name  varchar(60)  NOT NULL,
    bio           varchar(500),
    avatar_url    varchar(500),
    role          varchar(20)  NOT NULL DEFAULT 'USER',
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT app_user_email_key      UNIQUE (email),
    CONSTRAINT app_user_email_lower    CHECK (email = lower(email)),
    CONSTRAINT app_user_role_check     CHECK (role IN ('USER', 'ADMIN'))
);

-- Lo username e' univoco senza distinguere maiuscole/minuscole ("Mario" e "mario" sono lo stesso utente).
CREATE UNIQUE INDEX uq_app_user_username_lower ON app_user (lower(username));
