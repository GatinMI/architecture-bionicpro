package com.bionicpro.yandexshim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YandexOidcShimApplication {

    public static void main(String[] args) {
        SpringApplication.run(YandexOidcShimApplication.class, args);
    }

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}