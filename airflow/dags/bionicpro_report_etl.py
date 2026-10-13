"""ETL витрины отчётности BionicPRO.

Конвейер:
  1. load_crm         — полный снимок CRM (клиенты, протезы) в ClickHouse
  2. load_telemetry   — инкрементальная выгрузка телеметрии в ClickHouse
  3. build_view       — пересборка витрины report_prosthesis_daily
                        (агрегаты телеметрии в разрезе клиента и дня)
  4. update_watermark — метка «данные обработаны до даты N»

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

CRM_DSN = "dbname={n} user={u} password={p} host={h} port={pt}".format(
    n=os.environ["CRM_DB_NAME"], u=os.environ["CRM_DB_USER"],
    p=os.environ["CRM_DB_PASSWORD"], h=os.environ["CRM_DB_HOST"],
    pt=os.environ["CRM_DB_PORT"],
)
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


@task(**RETRIES, task_id="load_crm")
def load_crm() -> int:
    """Полный снимок CRM-таблиц в ClickHouse (таблицы небольшие)."""
    with _pg(CRM_DSN) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT id, full_name, email, COALESCE(phone, ''), created_at "
            "FROM customers ORDER BY id"
        )
        customers = cur.fetchall()
        cur.execute(
            "SELECT id, customer_id, model, serial_number, produced_at, "
            "COALESCE(delivery_date, produced_at) "
            "FROM prostheses ORDER BY id"
        )
        prostheses = cur.fetchall()

    ch = _ch()
    ch.command("TRUNCATE TABLE bionicpro.crm_customers")
    ch.command("TRUNCATE TABLE bionicpro.prostheses")
    ch.insert("bionicpro.crm_customers", customers,
              column_names=["id", "full_name", "email", "phone", "created_at"])
    ch.insert("bionicpro.prostheses", prostheses,
              column_names=["id", "customer_id", "model", "serial_number",
                            "produced_at", "delivery_date"])
    print(f"crm: {len(customers)} customers, {len(prostheses)} prostheses")
    return len(customers)


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


@task(**RETRIES, task_id="build_view")
def build_view() -> int:
    """Пересборка витрины: агрегаты телеметрии по клиенту/протезу/дню."""
    ch = _ch()
    ch.command("TRUNCATE TABLE bionicpro.report_prosthesis_daily")
    ch.query(
        """
        INSERT INTO bionicpro.report_prosthesis_daily
        SELECT
            p.customer_id,
            c.email,
            c.full_name,
            p.id,
            p.serial_number,
            p.model,
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
        JOIN bionicpro.prostheses AS p ON p.serial_number = t.prosthesis_serial
        JOIN bionicpro.crm_customers AS c ON c.id = p.customer_id
        GROUP BY p.customer_id, c.email, c.full_name,
                 p.id, p.serial_number, p.model, day
        """
    )
    total = ch.query(
        "SELECT count() FROM bionicpro.report_prosthesis_daily"
    ).result_rows[0][0]
    print(f"view: {total} rows rebuilt")
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


@dag(dag_id="bionicpro_report_etl",
     schedule="0 2 * * *",
     start_date=datetime(2026, 9, 1),
     catchup=False,
     tags=["reports", "etl"],
     doc_md="E2E: CRM + телеметрия -> ClickHouse, витрина по клиентам, водяной знак")
def bionicpro_report_etl() -> None:
    crm = load_crm()
    telemetry = load_telemetry()
    view = build_view()
    watermark = update_watermark()
    # view ждёт и CRM-снимок, и телеметрию (в Airflow 3 оператор & для
    # XCom-аргументов не поддерживается — зависимости ставим по одному)
    crm >> view
    telemetry >> view
    view >> watermark


bionicpro_report_etl()
