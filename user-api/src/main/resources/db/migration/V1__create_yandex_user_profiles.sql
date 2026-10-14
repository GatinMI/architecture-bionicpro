-- Профили пользователей, импортированные из Yandex ID,
-- и состояние согласия на использование этих данных.
CREATE TABLE yandex_user_profiles (
    user_id           VARCHAR(64)  PRIMARY KEY,
    yandex_account_id VARCHAR(64)  NOT NULL,
    yandex_login      VARCHAR(255),
    email             VARCHAR(255),
    full_name         VARCHAR(255),
    raw_profile       TEXT,
    consent_state     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    consented_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
