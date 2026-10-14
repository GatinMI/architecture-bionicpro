package com.bionicpro.reportapi.s3;

/**
 * S3-хранилище отчётов недоступно (сеть, отказ MinIO, ошибка записи).
 */
public class S3UnavailableException extends RuntimeException {

    public S3UnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
