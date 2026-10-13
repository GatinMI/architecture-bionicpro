package com.bionicpro.reportapi.service;

/** Витрина пуста (запуск ETL не дошёл до сборки витрины). */
public class NoDataException extends RuntimeException {
    public NoDataException() {
        super("Report view is empty");
    }
}
