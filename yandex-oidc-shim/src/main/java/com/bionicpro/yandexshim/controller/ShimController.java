package com.bionicpro.yandexshim.controller;

import com.bionicpro.yandexshim.client.YandexClient;
import com.bionicpro.yandexshim.config.ShimProperties;
import com.bionicpro.yandexshim.crypto.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;


@RestController
public class ShimController {

    private final ShimProperties props;
    private final YandexClient yandex;
    private final JwtUtil jwt;

    public ShimController(ShimProperties props, YandexClient yandex, JwtUtil jwt) {
        this.props = props;
        this.yandex = yandex;
        this.jwt = jwt;
    }

    @GetMapping("/authorize")
    public Mono<ResponseEntity<Void>> authorize(
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "state", required = false) String state) {

        // The client_id sent by Keycloak must match the clientId configured for this shim.
        if (clientId != null && !clientId.equals(props.getClientId())) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        if (redirectUri == null || redirectUri.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        if (responseType != null && !"code".equals(responseType)) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        StringBuilder url = new StringBuilder(props.getYandexAuthorizeUrl());
        url.append("?response_type=code");
        url.append("&client_id=").append(enc(props.getYandexClientId()));
        // Pass Keycloak's redirect_uri (the Keycloak broker endpoint) through to Yandex unchanged.
        url.append("&redirect_uri=").append(enc(redirectUri));
        url.append("&scope=").append(enc(props.getYandexScopes()));
        if (state != null && !state.isEmpty()) {
            url.append("&state=").append(enc(state));
        }

        return Mono.just(ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url.toString())).build());
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> token(ServerWebExchange exchange) {
        // Keycloak POSTs the code + client credentials as an application/x-www-form-urlencoded body.
        return exchange.getFormData().flatMap(form -> {
            String code = form.getFirst("code");
            String clientId = form.getFirst("client_id");
            String clientSecret = form.getFirst("client_secret");

            if (clientId == null || !clientId.equals(props.getClientId())
                    || clientSecret == null || !clientSecret.equals(props.getClientSecret())) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .<Map<String, Object>>body(Map.of("error", "invalid_client")));
            }
            if (code == null || code.isEmpty()) {
                return Mono.just(ResponseEntity.badRequest()
                        .<Map<String, Object>>body(Map.of("error", "invalid_request", "error_description", "missing code")));
            }

            return exchangeCode(code);
        });
    }

    private Mono<ResponseEntity<Map<String, Object>>> exchangeCode(String code) {
        return yandex.exchangeCodeForToken(code)
                .flatMap(tokenJson -> {
                    JsonNode at = tokenJson.get("access_token");
                    if (at == null || at.isNull() || at.asText().isEmpty()) {
                        Map<String, Object> err = new HashMap<>();
                        err.put("error", tokenJson.has("error") ? tokenJson.get("error").asText() : "yandex_token_error");
                        if (tokenJson.has("error_description")) {
                            err.put("error_description", tokenJson.get("error_description").asText());
                        }
                        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err));
                    }
                    return yandex.fetchUserInfo(at.asText())
                            .map(info -> ResponseEntity.ok(buildTokenResponse(tokenJson, info)));
                })
                .onErrorResume(e -> {
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", "server_error");
                    err.put("error_description", String.valueOf(e.getMessage()));
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err));
                });
    }

    private Map<String, Object> buildTokenResponse(JsonNode tokenJson, JsonNode info) {
        long now = System.currentTimeMillis() / 1000;
        String sub = textOrNull(info, "id");
        if (sub == null) {
            sub = "unknown";
        }
        String login = textOrNull(info, "login");
        String email = textOrNull(info, "default_email");
        if (email == null) {
            email = firstEmail(info);
        }
        String name = textOrNull(info, "real_name");
        if (name == null) {
            name = textOrNull(info, "display_name");
        }

        // Yandex profile -> OIDC id_token claims.
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", props.getIssuer());
        claims.put("aud", props.getClientId());
        claims.put("sub", sub);
        claims.put("iat", now);
        claims.put("exp", now + props.getIdTokenTtlSeconds());
        claims.put("preferred_username", login);
        claims.put("email", email);
        claims.put("name", name);
        claims.put("given_name", textOrNull(info, "first_name"));
        claims.put("family_name", textOrNull(info, "last_name"));
        claims.put("email_verified", true);
        // BionicPRO-specific: stable Yandex account id (for profile persistence) and the
        // claim the Keycloak claim-to-role mapper uses to grant the "user" realm role.
        claims.put("yandex_account_id", sub);
        claims.put("bionicpro_role", "user");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("access_token", tokenJson.get("access_token").asText());
        resp.put("token_type", "bearer");
        if (tokenJson.hasNonNull("expires_in")) {
            resp.put("expires_in", tokenJson.get("expires_in").asInt());
        }
        if (tokenJson.hasNonNull("refresh_token")) {
            resp.put("refresh_token", tokenJson.get("refresh_token").asText());
        }
        resp.put("scope", props.getYandexScopes());
        resp.put("id_token", jwt.signIdToken(claims));
        return resp;
    }

    @GetMapping("/userinfo")
    public Mono<ResponseEntity<Map<String, Object>>> userinfo(
            @RequestHeader(value = "Authorization", required = false) String auth) {

        if (auth == null || !auth.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        String accessToken = auth.substring("Bearer ".length());
        return yandex.fetchUserInfo(accessToken)
                .map(info -> ResponseEntity.ok(toUserInfoBody(info)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()));
    }

    private Map<String, Object> toUserInfoBody(JsonNode info) {
        String sub = textOrNull(info, "id");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sub", sub);
        m.put("preferred_username", textOrNull(info, "login"));
        m.put("email", textOrNull(info, "default_email") != null ? textOrNull(info, "default_email") : firstEmail(info));
        String name = textOrNull(info, "real_name");
        if (name == null) {
            name = textOrNull(info, "display_name");
        }
        m.put("name", name);
        m.put("given_name", textOrNull(info, "first_name"));
        m.put("family_name", textOrNull(info, "last_name"));
        m.put("yandex_account_id", sub);
        m.put("bionicpro_role", "user");
        m.put("email_verified", true);
        return m;
    }

    @GetMapping("/jwks")
    public Mono<ResponseEntity<Map<String, Object>>> jwks() {
        return Mono.just(ResponseEntity.ok(jwt.jwks()));
    }

    private static String firstEmail(JsonNode info) {
        JsonNode emails = info.get("emails");
        if (emails != null && emails.isArray() && emails.size() > 0) {
            return emails.get(0).asText();
        }
        return null;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private static String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
