package com.bionicpro.reportapi.model;

import java.util.List;

/** Сводка по протезу за запрошенный период. */
public record ProsthesisSummary(
    int days,
    float avgBatteryLevel,
    float avgSignalQuality,
    long totalMovements,
    long totalGestures,
    float recognitionErrorRate,
    float maxTemperatureC,
    List<String> errorCodes) {
}
