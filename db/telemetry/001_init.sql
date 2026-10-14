-- «База данных (PostgreSQL)» из исходной C4: телеметрия с чипов протезов.
-- Чипы присылают пакетные срезы раз в час (режим реального времени с
-- накоплением). Запускается при первом создании volume telemetry-db-data.

CREATE TABLE prosthetic_telemetry (
    id                  BIGSERIAL PRIMARY KEY,
    prosthesis_serial   VARCHAR(32) NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL,
    battery_level       NUMERIC(4,1) NOT NULL,      -- 0..100 %
    myosignal_quality   NUMERIC(4,1) NOT NULL,      -- 0..100, качество миосигнала
    movements_count     INT NOT NULL,               -- движения за период
    recognized_gestures INT NOT NULL,               -- корректно распознанные
    recognition_errors  INT NOT NULL,               -- ошибки распознавания
    error_codes         TEXT[] NOT NULL DEFAULT '{}',
    temperature_c       NUMERIC(4,1) NOT NULL       -- температура чипа
);

CREATE INDEX idx_telemetry_serial_time ON prosthetic_telemetry (prosthesis_serial, recorded_at);

-- Детерминированный генератор: ~75 дней почасовых срезов по каждому протезу
-- из CRM. Заряд батареи плавно падает на протяжении периода (эффект
-- «батарея садится»), качество миосигнала дрейфует, периодически всплывают
-- коды ошибок.
INSERT INTO prosthetic_telemetry
    (prosthesis_serial, recorded_at, battery_level, myosignal_quality,
     movements_count, recognized_gestures, recognition_errors, error_codes, temperature_c)
SELECT
    s.serial,
    ts,
    GREATEST(5, LEAST(100,
        (98 - EXTRACT(epoch FROM (now() - ts)) / (86400 * 30.0) * 4.2
         + (random() * 6 - 3))::numeric
    )),
    (78 + random() * 18)::numeric,
    (30 + random() * 220)::int,
    (25 + random() * 190)::int,
    (CASE WHEN random() < 0.30 THEN (1 + random() * 6)::int ELSE 0 END),
    CASE WHEN random() < 0.06
         THEN ARRAY(
                  SELECT code FROM (VALUES ('E_BATTERY_LOW'), ('E_MIO_DRIFT'),
                                           ('E_ACTUATOR_TIMEOUT'), ('E_HEAT')) AS c(code)
                  WHERE random() < 0.35)
         ELSE '{}'::text[]
    END,
    (31 + random() * 8)::numeric
FROM (VALUES ('BPN-2025-0001'), ('BPN-2025-0002'), ('BPN-2025-0003'),
             ('BPN-2026-0101'), ('BPN-2026-0102')) AS s(serial),
     generate_series(now() - interval '75 days', now() - interval '1 hour',
                     interval '1 hour') AS ts
ORDER BY s.serial, ts;
