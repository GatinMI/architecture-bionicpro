-- ============================================================================
-- CDC-слой витрины отчётности BionicPRO (Задание 4).
-- Создаётся при первом старте контейнера (docker-entrypoint-initdb.d).
--
-- Поток данных:
--   CRM PostgreSQL (wal_level=logical)
--     -> Debezium (kafka-connect) -> Kafka-топики bionicpro.crm.public.*
--     -> KafkaEngine (ниже) -> MaterializedView-«апсерт» -> зеркало CRM
--     -> MaterializedView-«витрина» (join с агрегатами телеметрии)
--     -> report_prosthesis_daily_v2 — новая витрина для report-api.
--
-- CRM больше не выгружается массовыми SELECT'ами (Airflow) — в OLAP попадают
-- только инкрементальные события, транзакционные операции CRM не страдают.
-- ============================================================================

-- ===== KafkaEngine: события CDC из Debezium =================================
-- Конвертер JsonConverter c schemas.enable=false отдаёт плоское JSON без
-- конверта: верхние поля — op, before, after, source, ts_ms.
-- Колонки String (before/after) получают JSON-текст вложенных объектов;
-- JSON null JSONEachRow записывает как пустую строку. Извлечение полей —
-- JSONExtract* в MV ниже.
--
-- ВАЖНО: PG DATE-колонки Debezium кодирует как int (дни от 1970-01-01),
-- поэтому created_at/produced_at/delivery_date — числа; преобразование
-- toDate(toUInt32(...)) в MV.

-- NOTE: kafka_group_name must differ from the Kafka Connect worker GROUP_ID
--       (bionicpro-cdc) — sharing one group id between Connect and ClickHouse
--       makes the broker report "Inconsistent group protocol" and the consumer
--       never joins the group. On a fresh start the topics are empty, so the
--       snapshot Debezium writes is picked up as new data. If migrating on top
--       of an already-populated topic, reset the ch-cdc group offsets to
--       earliest once (see README) so the initial snapshot is read.
CREATE TABLE IF NOT EXISTS bionicpro.crm_customers_kafka (
    op     String,
    before String,
    after  String,
    ts_ms  UInt64
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'bionicpro.crm.public.customers',
    kafka_group_name = 'ch-cdc',
    kafka_format = 'JSONEachRow',
    kafka_skip_broken_messages = 1;

CREATE TABLE IF NOT EXISTS bionicpro.crm_prostheses_kafka (
    op     String,
    before String,
    after  String,
    ts_ms  UInt64
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'bionicpro.crm.public.prostheses',
    kafka_group_name = 'ch-cdc',
    kafka_format = 'JSONEachRow',
    kafka_skip_broken_messages = 1;

-- ===== Зеркала CRM (upsert в реальном времени) ==============================
-- ReplacingMergeTree: каждое CDC-событие — новая версия строки;
-- актуальная (max version = ts_ms события) видна через FINAL.
-- deleted=1 — для событий op='d' (данные берём из "before").

CREATE TABLE IF NOT EXISTS bionicpro.crm_customers_cdc (
    id         UInt64,
    full_name  String,
    email      String,
    phone      Nullable(String),
    created_at Nullable(Date),
    deleted    UInt8,
    version    UInt64
) ENGINE = ReplacingMergeTree(version)
  ORDER BY id;

CREATE TABLE IF NOT EXISTS bionicpro.crm_prostheses_cdc (
    id            UInt64,
    customer_id   UInt64,
    model         String,
    serial_number String,
    produced_at   Nullable(Date),
    delivery_date Nullable(Date),
    deleted       UInt8,
    version       UInt64
) ENGINE = ReplacingMergeTree(version)
  ORDER BY id;

-- Апсерт-зеркала из Kafka. src = "after", для op='d' (after пусто) — "before".
-- DATE-значения приходят как порядковый номер дня (int) -> toDate(toUInt32(...)).
CREATE MATERIALIZED VIEW IF NOT EXISTS bionicpro.crm_customers_cdc_mv TO bionicpro.crm_customers_cdc AS
SELECT
    toUInt64(JSONExtractRaw(src, 'id'))                          AS id,
    JSONExtractString(src, 'full_name')                          AS full_name,
    JSONExtractString(src, 'email')                              AS email,
    NULLIF(JSONExtractString(src, 'phone'), '')                  AS phone,
    CASE WHEN JSONExtractRaw(src, 'created_at') IN ('', 'null')
         THEN NULL
         ELSE toDate(toUInt32(JSONExtractRaw(src, 'created_at')))
    END                                                          AS created_at,
    (op = 'd')::UInt8                                            AS deleted,
    ts_ms                                                        AS version
FROM (
    SELECT op, ts_ms,
        multiIf(after IN ('', 'null'), before, after) AS src
    FROM bionicpro.crm_customers_kafka
);

CREATE MATERIALIZED VIEW IF NOT EXISTS bionicpro.crm_prostheses_cdc_mv TO bionicpro.crm_prostheses_cdc AS
SELECT
    toUInt64(JSONExtractRaw(src, 'id'))                          AS id,
    toUInt64(JSONExtractRaw(src, 'customer_id'))                 AS customer_id,
    JSONExtractString(src, 'model')                              AS model,
    JSONExtractString(src, 'serial_number')                      AS serial_number,
    CASE WHEN JSONExtractRaw(src, 'produced_at') IN ('', 'null')
         THEN NULL
         ELSE toDate(toUInt32(JSONExtractRaw(src, 'produced_at')))
    END                                                          AS produced_at,
    CASE WHEN JSONExtractRaw(src, 'delivery_date') IN ('', 'null')
         THEN NULL
         ELSE toDate(toUInt32(JSONExtractRaw(src, 'delivery_date')))
    END                                                          AS delivery_date,
    (op = 'd')::UInt8                                            AS deleted,
    ts_ms                                                        AS version
FROM (
    SELECT op, ts_ms,
        multiIf(after IN ('', 'null'), before, after) AS src
    FROM bionicpro.crm_prostheses_kafka
);

-- ===== Агрегаты телеметрии (собирает Airflow) ================================
-- Чистая телеметрия в разрезе (протез, день); join с CRM — только в витрине.

CREATE TABLE IF NOT EXISTS bionicpro.telemetry_prosthesis_daily (
    prosthesis_serial        String,
    day                      Date,
    avg_battery_level        Float32,
    min_battery_level        Float32,
    avg_signal_quality       Float32,
    total_movements          UInt64,
    total_gestures           UInt64,
    total_recognition_errors UInt64,
    max_temperature_c        Float32,
    error_codes              Array(String),
    measurements             UInt32
) ENGINE = MergeTree
  PARTITION BY toYYYYMM(day)
  ORDER BY (prosthesis_serial, day);

-- ===== Триггер пересборки витрины (вставляет Airflow после ETL) =============

CREATE TABLE IF NOT EXISTS bionicpro.wide_refresh_trigger (
    run_id     UInt64,
    ts_ms      UInt64,
    updated_at DateTime
) ENGINE = MergeTree
  ORDER BY run_id;

-- ===== Новая витрина отчётности: CRM (CDC) × телеметрия (ETL) ===============
-- Идентичность строки — (customer_id, prosthesis_id, day); версия —
-- epoch-миллисекунды (ts_ms CDC-события или ts триггера refresh), поэтому
-- при дедупликации «позже пришедшее побеждает». ORDER BY по customer_id —
-- prefix-скан по пользователю для report-api.

CREATE TABLE IF NOT EXISTS bionicpro.report_prosthesis_daily_v2 (
    customer_id              UInt64,
    customer_email           String,
    customer_full_name       String,
    prosthesis_id            UInt64,
    prosthesis_serial        String,
    prosthesis_model         String,
    day                      Date,
    avg_battery_level        Float32,
    min_battery_level        Float32,
    avg_signal_quality       Float32,
    total_movements          UInt64,
    total_gestures           UInt64,
    total_recognition_errors UInt64,
    max_temperature_c        Float32,
    error_codes              Array(String),
    measurements             UInt32,
    deleted                  UInt8 DEFAULT 0,
    version                  UInt64
) ENGINE = ReplacingMergeTree(version)
  PARTITION BY toYYYYMM(day)
  ORDER BY (customer_id, prosthesis_id, day);

-- CDC-событие по протезу (новое/обновлённое): пересборка строк этого протеза
CREATE MATERIALIZED VIEW IF NOT EXISTS bionicpro.report_v2_mv_prostheses TO bionicpro.report_prosthesis_daily_v2 AS
SELECT
    c.id AS customer_id, c.email AS customer_email, c.full_name AS customer_full_name,
    p.id AS prosthesis_id, p.serial_number AS prosthesis_serial, p.model AS prosthesis_model,
    t.day, t.avg_battery_level, t.min_battery_level, t.avg_signal_quality,
    t.total_movements, t.total_gestures, t.total_recognition_errors,
    t.max_temperature_c, t.error_codes, t.measurements,
    0 AS deleted,
    p.version AS version
FROM bionicpro.crm_prostheses_cdc AS p
JOIN bionicpro.crm_customers_cdc AS c FINAL ON c.id = p.customer_id
JOIN bionicpro.telemetry_prosthesis_daily AS t ON t.prosthesis_serial = p.serial_number
WHERE p.deleted = 0 AND c.deleted = 0;

-- CDC-событие по клиенту (например, смена ФИО/телефона): пересборка строк
-- всех его протезов
CREATE MATERIALIZED VIEW IF NOT EXISTS bionicpro.report_v2_mv_customers TO bionicpro.report_prosthesis_daily_v2 AS
SELECT
    c.id AS customer_id, c.email AS customer_email, c.full_name AS customer_full_name,
    p.id AS prosthesis_id, p.serial_number AS prosthesis_serial, p.model AS prosthesis_model,
    t.day, t.avg_battery_level, t.min_battery_level, t.avg_signal_quality,
    t.total_movements, t.total_gestures, t.total_recognition_errors,
    t.max_temperature_c, t.error_codes, t.measurements,
    0 AS deleted,
    c.version AS version
FROM bionicpro.crm_customers_cdc AS c
JOIN bionicpro.crm_prostheses_cdc AS p FINAL ON p.customer_id = c.id
JOIN bionicpro.telemetry_prosthesis_daily AS t ON t.prosthesis_serial = p.serial_number
WHERE c.deleted = 0 AND p.deleted = 0;

-- После ETL Airflow: полная пересборка витрины (version = ts триггера).
-- Закрывает новую телеметрию, а вместе с ALTER DELETE старых версий,
-- выполняемым в Airflow, — и строки по удалённым из CRM протезам.
CREATE MATERIALIZED VIEW IF NOT EXISTS bionicpro.report_v2_mv_refresh TO bionicpro.report_prosthesis_daily_v2 AS
SELECT
    c.id AS customer_id, c.email AS customer_email, c.full_name AS customer_full_name,
    p.id AS prosthesis_id, p.serial_number AS prosthesis_serial, p.model AS prosthesis_model,
    t.day, t.avg_battery_level, t.min_battery_level, t.avg_signal_quality,
    t.total_movements, t.total_gestures, t.total_recognition_errors,
    t.max_temperature_c, t.error_codes, t.measurements,
    0 AS deleted,
    tr.ts_ms AS version
FROM bionicpro.wide_refresh_trigger AS tr
JOIN (SELECT * FROM bionicpro.crm_customers_cdc FINAL WHERE deleted = 0) AS c ON 1 = 1
JOIN (SELECT * FROM bionicpro.crm_prostheses_cdc FINAL WHERE deleted = 0) AS p ON p.customer_id = c.id
JOIN bionicpro.telemetry_prosthesis_daily AS t ON t.prosthesis_serial = p.serial_number;
