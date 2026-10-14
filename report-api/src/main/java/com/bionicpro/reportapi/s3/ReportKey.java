package com.bionicpro.reportapi.s3;

import java.time.LocalDate;

/**
 * Ключ объекта в S3: {email}/g{поколение ETL}/{от}_{до}.json
 *
 * Ключ детерминирован — повторный запрос того же отчёта всегда попадает
 * в один и тот же объект (O(1), без листинга бакета).
 *
 * «Поколение» — дата, до которой ETL обработал данные (watermark).
 * Когда Airflow обрабатывает новые данные, поколение сдвигается и ключ
 * меняется: отчёт формируется заново под новым URL, старые URL не могут
 * отдавать устаревшие данные — кеш CDN обновляется без purge-механики.
 * Поколения в бакете чистятся ILM-правилом MinIO (см. minio-init).
 */
public final class ReportKey {

    private final String key;

    private ReportKey(String key) {
        this.key = key;
    }


    public static ReportKey of(String email, LocalDate generation, LocalDate from, LocalDate to) {
        String fromPart = from == null ? "start" : from.toString();
        String toPart = to == null ? "end" : to.toString();
        return new ReportKey(email + "/g" + generation + "/" + fromPart + "_" + toPart + ".json");
    }

    public String value() {
        return key;
    }

    @Override
    public String toString() {
        return key;
    }
}
