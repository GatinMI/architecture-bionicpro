package com.bionicpro.reportapi.model;

import java.time.LocalDate;
import java.util.List;

/** Срез витрины за один день по одному протезу (агрегаты Airflow). */
public record DailyMetrics(
    LocalDate day,
    float avgBatteryLevel,
    float minBatteryLevel,
    float avgSignalQuality,
    long totalMovements,
    long totalGestures,
    long totalRecognitionErrors,
    float maxTemperatureC,
    List<String> errorCodes,
    int measurements) {
}
