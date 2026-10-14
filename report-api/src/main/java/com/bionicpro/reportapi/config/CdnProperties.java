package com.bionicpro.reportapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "cdn")
public record CdnProperties(
    String baseUrl) {
}
