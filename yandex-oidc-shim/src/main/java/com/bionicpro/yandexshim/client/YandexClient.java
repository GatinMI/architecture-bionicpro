package com.bionicpro.yandexshim.client;

import com.bionicpro.yandexshim.config.ShimProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HTTP client for the Yandex ID API: exchanges the authorization code for a token,
 * then fetches the user profile.
 *
 * <p>Все запросы и ответы к API Yandex логируются на уровне DEBUG
 * (включается через {@code LOGGING_LEVEL_COM_BIONICPRO_YANDEXSHIM=DEBUG},
 * см. docker-compose.yaml).
 *
 * <p>Внимание: при включённом DEBUG в логах содержатся секреты
 * (client_secret в теле /token, access-токены Yandex в Authorization),
 * что допустимо только для локальной разработки.
 */
@Component
public class YandexClient {

    private static final Logger log = LoggerFactory.getLogger(YandexClient.class);

    private final WebClient webClient;
    private final ShimProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public YandexClient(WebClient.Builder builder, ShimProperties props) {
        this.webClient = builder.build();
        this.props = props;
    }

    public Mono<JsonNode> exchangeCodeForToken(String code) {
        String url = props.getYandexTokenUrl();
        String body = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&client_id=" + enc(props.getYandexClientId())
                + "&client_secret=" + enc(props.getYandexClientSecret());
        log.debug("YANDEX REQUEST  >>> POST {} | Content-Type: application/x-www-form-urlencoded | body: [{}]",
                url, body);
        return webClient.post()
                .uri(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(s -> {
                    log.debug("YANDEX RESPONSE <<< POST {} | status: 2xx | body: [{}]", url, s);
                    return parse(s, "Yandex token response");
                })
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.debug("YANDEX RESPONSE <<< POST {} | status: {} | body: [{}]",
                            url, e.getStatusCode().value(), e.getResponseBodyAsString());
                    return Mono.error(e);
                });
    }

    public Mono<JsonNode> fetchUserInfo(String accessToken) {
        String url = props.getYandexInfoUrl() + "?format=json";
        log.debug("YANDEX REQUEST  >>> GET {} | Authorization: OAuth {}", url, accessToken);
        return webClient.get()
                .uri(url)
                .header("Authorization", "OAuth " + accessToken)
                .retrieve()
                .bodyToMono(String.class)
                .map(s -> {
                    log.debug("YANDEX RESPONSE <<< GET {} | status: 2xx | body: [{}]", url, s);
                    return parse(s, "Yandex info response");
                })
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.debug("YANDEX RESPONSE <<< GET {} | status: {} | body: [{}]",
                            url, e.getStatusCode().value(), e.getResponseBodyAsString());
                    return Mono.error(e);
                });
    }

    private JsonNode parse(String body, String label) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(label + " is invalid: " + body, e);
        }
    }

    private static String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
