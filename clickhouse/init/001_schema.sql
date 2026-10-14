-- Схема OLAP (ClickHouse) для витрины отчётности BionicPRO.
-- Создаётся при первом старте контейнера (docker-entrypoint-initdb.d).
-- CRM-данные в OLAP теперь попадают через CDC (Debezium -> Kafka -> KafkaEngine,
-- см. 002_cdc.sql); телеметрию выгружает Airflow (airflow/dags/bionicpro_report_etl.py).

CREATE DATABASE IF NOT EXISTS bionicpro;

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

-- Водяной знак ETL: до какого дня обработаны данные.
-- Сервис отчётов по нему отвечает только за обработанные периоды
CREATE TABLE IF NOT EXISTS bionicpro.etl_watermark (
    run_id          UInt64,
    processed_up_to Date,
    updated_at      DateTime
) ENGINE = ReplacingMergeTree(updated_at)
  ORDER BY run_id;
