package com.bionicpro.reportapi.ch;

import com.bionicpro.reportapi.model.DailyMetrics;

/** Строка витрины: атрибуты протеза + подневной агрегат. */
public record ProsthesisDayRow(
    int prosthesisId,
    String serial,
    String model,
    DailyMetrics day) {
}
