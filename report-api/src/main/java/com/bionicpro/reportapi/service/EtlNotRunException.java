package com.bionicpro.reportapi.service;

/** Airflow ещё не выполнил ни одного успешного прогона (нет watermark). */
public class EtlNotRunException extends RuntimeException {
    public EtlNotRunException() {
        super("ETL has not processed any data yet");
    }
}
