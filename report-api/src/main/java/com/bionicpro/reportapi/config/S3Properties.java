package com.bionicpro.reportapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "s3")
public record S3Properties(
    String endpoint,
    String region,
    String bucket,
    String accessKey,
    String secretKey) {
}
