package com.bionicpro.reportapi.model;

import java.util.List;

/** Отчёт по одному протезу пользователя: сводка + подневные агрегаты. */
public record ProsthesisReport(
    int prosthesisId,
    String serial,
    String model,
    ProsthesisSummary summary,
    List<DailyMetrics> daily) {
}
