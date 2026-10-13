-- Схема OLAP (ClickHouse) для витрины отчётности BionicPRO.
-- Создаётся при первом старте контейнера (docker-entrypoint-initdb.d).
-- Данные в таблицы загружает Airflow (airflow/dags/bionicpro_report_etl.py).

CREATE DATABASE IF NOT EXISTS bionicpro;

-- Снимок клиентов CRM (DAG перезаписывает таблицу целиком каждый запуск)
CREATE TABLE IF NOT EXISTS bionicpro.crm_customers (
    id         UInt64,
    full_name  String,
    email      String,
    phone      String,
    created_at Date
) ENGINE = MergeTree
  ORDER BY id;

-- Снимок протезов CRM
CREATE TABLE IF NOT EXISTS bionicpro.prostheses (
    id             UInt64,
    customer_id    UInt64,
    model          String,
    serial_number  String,
    produced_at    Date,
    delivery_date  Date
) ENGINE = MergeTree
  ORDER BY id;

-- Сырая телеметрия. ReplacingMergeTree: повторные загрузки того же среза
-- (повторный запуск ETL) схлопываются по (serial, recorded_at)
CREATE TABLE IF NOT EXISTS bionicpro.telemetry (
    prosthesis_serial   String,
    recorded_at         DateTime,
    battery_level       Float32,
    myosignal_quality   Float32,
    movements_count     UInt32,
    recognized_gestures UInt32,
    recognition_errors  UInt32,
    error_codes         Array(String),
    temperature_c       Float32
) ENGINE = ReplacingMergeTree
  PARTITION BY toYYYYMM(recorded_at)
  ORDER BY (prosthesis_serial, recorded_at);

-- Витрина отчётности: агрегаты телеметрии в разрезе клиента и дня.
-- ORDER BY (customer_email, prosthesis_id, day) — быстрый срез по пользователю
-- (prefix-скан), PARTITION BY месяц — быстрое удаление/пересборка периодов
CREATE TABLE IF NOT EXISTS bionicpro.report_prosthesis_daily (
    customer_id             UInt64,
    customer_email          String,
    customer_full_name      String,
    prosthesis_id           UInt64,
    prosthesis_serial       String,
    prosthesis_model        String,
    day                     Date,
    avg_battery_level       Float32,
    min_battery_level       Float32,
    avg_signal_quality      Float32,
    total_movements         UInt64,
    total_gestures          UInt64,
    total_recognition_errors UInt64,
    max_temperature_c       Float32,
    error_codes             Array(String),
    measurements            UInt32
) ENGINE = MergeTree
  PARTITION BY toYYYYMM(day)
  ORDER BY (customer_email, prosthesis_id, day);

-- Водяной знак ETL: до какого дня обработаны данные.
-- Сервис отчётов по нему отвечает только за обработанные периоды
CREATE TABLE IF NOT EXISTS bionicpro.etl_watermark (
    run_id          UInt64,
    processed_up_to Date,
    updated_at      DateTime
) ENGINE = ReplacingMergeTree(updated_at)
  ORDER BY run_id;
