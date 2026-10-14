package com.bionicpro.reportapi.s3;

import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;


public class S3ReportStore {

    private final S3AsyncClient s3;
    private final String bucket;
    private final String cdnBaseUrl;

    public S3ReportStore(S3AsyncClient s3, String bucket, String cdnBaseUrl) {
        this.s3 = s3;
        this.bucket = bucket;
        this.cdnBaseUrl = cdnBaseUrl;
    }


    public Mono<Boolean> exists(String key) {
        CompletableFuture<Boolean> future = s3
            .headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            .handle((response, error) -> {
                if (error == null) {
                    return Boolean.TRUE;
                }
                Throwable cause = unwrap(error);
                if (cause instanceof S3Exception e && e.statusCode() == 404) {
                    return Boolean.FALSE;
                }
                throw new S3UnavailableException(
                    "S3 HEAD request failed: " + cause.getMessage(), cause);
            });
        return Mono.fromFuture(future);
    }


    public Mono<Void> put(String key, byte[] json) {
        CompletableFuture<Void> future = s3
            .putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/json; charset=utf-8")
                    .build(),
                AsyncRequestBody.fromBytes(json))
            .handle((response, error) -> {
                if (error != null) {
                    Throwable cause = unwrap(error);
                    if (!(cause instanceof S3Exception)) {
                        throw new S3UnavailableException(
                            "S3 PUT request failed: " + cause.getMessage(), cause);
                    }
                }
                return null;
            });
        return Mono.fromFuture(future);
    }


    public String cdnUrl(String key) {
        return cdnBaseUrl + "/" + bucket + "/" + key;
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException ce && ce.getCause() != null
            ? ce.getCause()
            : error;
    }
}
