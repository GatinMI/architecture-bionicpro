package com.bionicpro.reportapi.config;

import com.bionicpro.reportapi.s3.S3ReportStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties({S3Properties.class, CdnProperties.class})
public class S3Config {

    @Bean
    public S3AsyncClient s3AsyncClient(S3Properties s3) {
        return S3AsyncClient.builder()
            .endpointOverride(URI.create(s3.endpoint()))
            .region(Region.of(s3.region()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .build();
    }

    @Bean
    public S3ReportStore reportStore(S3AsyncClient s3AsyncClient,
                                     S3Properties s3,
                                     CdnProperties cdn) {
        return new S3ReportStore(s3AsyncClient, s3.bucket(), cdn.baseUrl());
    }
}
