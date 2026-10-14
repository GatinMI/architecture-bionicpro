-- Эмуляция БД CRM (Битрикс24) для BionicPRO.
-- Запускается при первом создании volume crm-db-data (docker-entrypoint-initdb.d).

CREATE TABLE customers (
    id          BIGSERIAL PRIMARY KEY,
    full_name   VARCHAR(120) NOT NULL,
    email       VARCHAR(160) NOT NULL UNIQUE,
    phone       VARCHAR(32),
    created_at  DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE TABLE prostheses (
    id             BIGSERIAL PRIMARY KEY,
    customer_id    BIGINT NOT NULL REFERENCES customers (id),
    model          VARCHAR(60) NOT NULL,
    serial_number  VARCHAR(32) NOT NULL UNIQUE,
    produced_at    DATE NOT NULL,
    delivery_date  DATE
);

CREATE INDEX idx_prostheses_customer ON prostheses (customer_id);

-- Клиенты. e-mail совпадает с e-mail пользователей Keycloak (reports-realm) —
-- именно по e-mail отчётный сервис связывает JWT с клиентом CRM.
INSERT INTO customers (full_name, email, phone, created_at) VALUES
    ('Иванов Игорь Иванович',      'prothetic1@example.com', '+7 900 100-00-01', '2025-10-02'),
    ('Петрова Елена Петровна',     'prothetic2@example.com', '+7 900 100-00-02', '2025-11-05'),
    ('Сидоров Дмитрий Сидорович',  'prothetic3@example.com', '+7 900 100-00-03', '2025-12-10'),
    ('Иванов Андрей Андреевич',    'user1@example.com',      '+7 900 100-00-04', '2025-10-20'),
    ('Смирнова Ольга Сергеевна',   'user2@example.com',      '+7 900 100-00-05', '2025-11-18'),
    ('Джон Дой',                   'john@example.com',       '+7 900 100-00-06', '2026-01-12'),
    ('Джейн Смит',                 'jane@example.com',       '+7 900 100-00-07', '2026-02-08'),
    ('Алекс Джонсон',              'alex@example.com',       '+7 900 100-00-08', '2026-03-14');

-- Заказанные/выданные протезы. У первого клиента два протеза (сценарий
-- «несколько протезов у одного пользователя»).
INSERT INTO prostheses (customer_id, model, serial_number, produced_at, delivery_date) VALUES
    (1, 'Bionic Hand X1', 'BPN-2025-0001', '2025-11-10', '2025-11-24'),
    (1, 'Bionic Hand X2', 'BPN-2026-0101', '2026-02-15', '2026-02-28'),
    (2, 'Bionic Arm A3',  'BPN-2025-0002', '2025-12-01', '2025-12-15'),
    (3, 'Bionic Hand X1', 'BPN-2025-0003', '2026-01-20', '2026-02-02'),
    (6, 'Bionic Arm A3',  'BPN-2026-0102', '2026-03-10', '2026-03-25');
