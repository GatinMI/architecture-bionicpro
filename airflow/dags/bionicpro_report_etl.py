"""ETL витрины отчётности BionicPRO.

Конвейер:
  1. load_telemetry      — инкрементальная выгрузка телеметрии в ClickHouse
  2. build_telemetry_view — агрегаты телеметрии в разрезе (протез, день)
  3. update_watermark    — метка «данные обработаны до даты N»
  4. refresh_wide_view   — триггер полной пересборки витрины
                           report_prosthesis_daily_v2 (MaterializedView в CH,
                           см. clickhouse/init/002_cdc.sql)

Данные CRM (клиенты, протезы) в ClickHouse попадают не отсюда, а через
Debezium CDC в реальном времени: CRM PG -> Kafka -> KafkaEngine -> зеркала.
Массовая выгрузка CRM (задача load_crm) убрана — она нагружала OLTP-базу.

Расписание: ежедневно в 02:00. catchup=False — бэкилл за прошедшие периоды
при первом запуске не выполняется; период, который ещё не обработан ETL,
сервис отчётов не отдаёт (см. report-api).
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone

import psycopg2
from clickhouse_connect import get_client
from airflow.sdk import dag, task

TELEMETRY_DSN = "dbname={n} user={u} password={p} host={h} port={pt}".format(
    n=os.environ["TELEMETRY_DB_NAME"], u=os.environ["TELEMETRY_DB_USER"],
    p=os.environ["TELEMETRY_DB_PASSWORD"], h=os.environ["TELEMETRY_DB_HOST"],
    pt=os.environ["TELEMETRY_DB_PORT"],
)
CH = dict(
    host=os.environ["CLICKHOUSE_HOST"],
    port=int(os.environ["CLICKHOUSE_PORT"]),
    username=os.environ["CLICKHOUSE_USER"],
    password=os.environ["CLICKHOUSE_PASSWORD"],
    database=os.environ["CLICKHOUSE_DB"],
)

RETRIES = dict(retries=2, retry_delay=timedelta(minutes=1))


def _ch():
    return get_client(**CH)


def _pg(dsn: str):
    return psycopg2.connect(dsn)


def _utc(dt):
    """CH хранит время в UTC; приводим tz-aware timestamp'ы PG к наивным UTC."""
    if dt.tzinfo is not None:
        dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
    return dt


@task(**RETRIES, task_id="load_telemetry")
def load_telemetry() -> int:
    """Инкремент: выгружаем из PG срезы, которых ещё нет в ClickHouse."""
    ch = _ch()
    last = ch.query(
        "SELECT max(recorded_at) FROM bionicpro.telemetry FINAL"
    ).result_rows[0][0]

    with _pg(TELEMETRY_DSN) as conn, conn.cursor() as cur:
        if last:
            cur.execute(
                "SELECT prosthesis_serial, recorded_at, battery_level, "
                "myosignal_quality, movements_count, recognized_gestures, "
                "recognition_errors, error_codes, temperature_c "
                "FROM prosthetic_telemetry WHERE recorded_at > %s "
                "ORDER BY recorded_at",
                (last,),
            )
        else:
            cur.execute(
                "SELECT prosthesis_serial, recorded_at, battery_level, "
                "myosignal_quality, movements_count, recognized_gestures, "
                "recognition_errors, error_codes, temperature_c "
                "FROM prosthetic_telemetry ORDER BY recorded_at"
            )
        rows = [
            (serial, _utc(ts), float(batt), float(qual), mov, gest, err, codes, float(temp))
            for serial, ts, batt, qual, mov, gest, err, codes, temp in cur.fetchall()
        ]

    if rows:
        ch.insert("bionicpro.telemetry", rows,
                  column_names=["prosthesis_serial", "recorded_at", "battery_level",
                                "myosignal_quality", "movements_count",
                                "recognized_gestures", "recognition_errors",
                                "error_codes", "temperature_c"])
    print(f"telemetry: {len(rows)} new samples (last loaded: {last})")
    return len(rows)


@task(**RETRIES, task_id="build_telemetry_view")
def build_telemetry_view() -> int:
    """Чистые агрегаты телеметрии по (протез, день) без join'а с CRM.
    CRM-атрибуты добавляются в витрину report_prosthesis_daily_v2 на стороне
    ClickHouse (MaterializedView поверх зеркал CDC, см. 002_cdc.sql)."""
    ch = _ch()
    ch.command("TRUNCATE TABLE bionicpro.telemetry_prosthesis_daily")
    ch.query(
        """
        INSERT INTO bionicpro.telemetry_prosthesis_daily
        SELECT
            t.prosthesis_serial,
            toDate(t.recorded_at)                                    AS day,
            avg(t.battery_level)                                     AS avg_battery_level,
            min(t.battery_level)                                     AS min_battery_level,
            avg(t.myosignal_quality)                                 AS avg_signal_quality,
            sum(t.movements_count)                                   AS total_movements,
            sum(t.recognized_gestures)                               AS total_gestures,
            sum(t.recognition_errors)                                AS total_recognition_errors,
            max(t.temperature_c)                                     AS max_temperature_c,
            arraySort(arrayDistinct(flatten(
                groupArrayIf(t.error_codes, length(t.error_codes) > 0)
            )))                                                      AS error_codes,
            count()                                                  AS measurements
        FROM bionicpro.telemetry AS t FINAL
        GROUP BY t.prosthesis_serial, day
        """
    )
    total = ch.query(
        "SELECT count() FROM bionicpro.telemetry_prosthesis_daily"
    ).result_rows[0][0]
    print(f"telemetry view: {total} rows rebuilt")
    return total


@task(**RETRIES, task_id="update_watermark")
def update_watermark() -> str:
    """Метка «данные обработаны до даты N» для сервиса отчётов."""
    ch = _ch()
    run_id = ch.query(
        "SELECT coalesce(max(run_id), 0) + 1 FROM bionicpro.etl_watermark FINAL"
    ).result_rows[0][0]
    up_to = ch.query(
        "SELECT toDate(max(recorded_at)) FROM bionicpro.telemetry FINAL"
    ).result_rows[0][0]
    ch.insert("bionicpro.etl_watermark",
              [(run_id, up_to, datetime.now(timezone.utc))],
              column_names=["run_id", "processed_up_to", "updated_at"])
    print(f"watermark: run {run_id}, processed up to {up_to}")
    return str(up_to)


@task(**RETRIES, task_id="refresh_wide_view")
def refresh_wide_view() -> int:
    """Полная пересборка витрины report_prosthesis_daily_v2.

    Вставляем строку-триггер: MaterializedView report_v2_mv_refresh на её
    основе пересобирает все строки витрины (CRM-зеркала CDC × агрегаты
    телеметрии) с version = ts. Затем удаляем более старые версии — это
    чистит строки по протезам, удалённым из CRM между запусками ETL.
    """
    ch = _ch()
    run_id = ch.query(
        "SELECT coalesce(max(run_id), 0) + 1 FROM bionicpro.wide_refresh_trigger"
    ).result_rows[0][0]
    ts = int(datetime.now(timezone.utc).timestamp() * 1000)
    ch.insert("bionicpro.wide_refresh_trigger",
              [(run_id, ts, datetime.now(timezone.utc))],
              column_names=["run_id", "ts_ms", "updated_at"])
    ch.command(
        "ALTER TABLE bionicpro.report_prosthesis_daily_v2 "
        f"DELETE WHERE version < {ts} SETTINGS mutations_sync = 1"
    )
    total = ch.query(
        "SELECT count() FROM bionicpro.report_prosthesis_daily_v2 FINAL"
    ).result_rows[0][0]
    print(f"wide view: refresh run {run_id} (ts {ts}), {total} rows")
    return total


@dag(dag_id="bionicpro_report_etl",
     schedule="0 2 * * *",
     start_date=datetime(2026, 9, 1),
     catchup=False,
     tags=["reports", "etl"],
     doc_md="ETL: телеметрия -> ClickHouse + refresh витрины; "
            "CRM в CH приходит через Debezium CDC (Задание 4)")
def bionicpro_report_etl() -> None:
    telemetry = load_telemetry()
    view = build_telemetry_view()
    watermark = update_watermark()
    refresh = refresh_wide_view()
    telemetry >> view >> watermark >> refresh


bionicpro_report_etl()
