package com.bionicpro.reportapi.service;

import java.time.LocalDate;

/** Запрошенный период выходит за пределы, уже обработанный Airflow. */
public class PeriodNotProcessedException extends RuntimeException {

    private final LocalDate availableUntil;

    public PeriodNotProcessedException(LocalDate availableUntil) {
        super("Requested period is not processed by ETL yet");
        this.availableUntil = availableUntil;
    }

    public LocalDate availableUntil() {
        return availableUntil;
    }
}
