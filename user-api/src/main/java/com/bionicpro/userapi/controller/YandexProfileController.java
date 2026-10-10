package com.bionicpro.userapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Профиль пользователя из Yandex ID и согласие на использование этих данных.
 *
 * <p>Саму аутентификацию через Yandex ID выполняет Keycloak (Identity Brokering):
 * профиль из Yandex попадает в Keycloak при логине, а в токен bionicpro-auth —
 * через protocol mappers (claim {@code yandex_account_id} и др.). Сервис получает
 * данные профиля из валидированного JWT и после согласия пользователя сохраняет их в БД.
 */
@RestController
public class YandexProfileController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DatabaseClient databaseClient;

    public YandexProfileController(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Состояние yandex-профиля текущего пользователя.
     * 404, если пользователь не пришёл через Yandex ID (нет claim yandex_account_id).
     */
    @GetMapping("/api/users/me/yandex")
    public Mono<ResponseEntity<Map<String, Object>>> yandexProfile(
            @AuthenticationPrincipal JwtAuthenticationToken jwtAuth) {

        String yandexAccountId = jwtAuth.getToken().getClaimAsString("yandex_account_id");
        if (yandexAccountId == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "not_a_yandex_user")));
        }
        String userId = userIdOf(jwtAuth);
        return databaseClient.sql("SELECT * FROM yandex_user_profiles WHERE user_id = $1")
                .bind(0, userId)
                .map(row -> storedProfileResponse(row))
                .first()
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.ok(pendingProfileResponse(jwtAuth)));
    }

    /**
     * Запись согласия (true) или отказа (false) на использование данных Yandex ID.
     * При согласии сервис сохраняет профиль пользователя из Yandex в БД.
     */
    @PostMapping("/api/users/me/yandex-consent")
    public Mono<ResponseEntity<Map<String, Object>>> consent(
            @AuthenticationPrincipal JwtAuthenticationToken jwtAuth,
            @RequestBody Map<String, Object> body) {

        String yandexAccountId = jwtAuth.getToken().getClaimAsString("yandex_account_id");
        if (yandexAccountId == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "not_a_yandex_user")));
        }
        boolean granted = Boolean.TRUE.equals(body.get("consent"));
        String userId = userIdOf(jwtAuth);
        String state = granted ? "GRANTED" : "DENIED";
        String rawProfile = toJson(profileClaims(jwtAuth));

        String sql = """
                INSERT INTO yandex_user_profiles
                    (user_id, yandex_account_id, yandex_login, email, full_name, raw_profile, consent_state, consented_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, now())
                ON CONFLICT (user_id) DO UPDATE SET
                    yandex_login = EXCLUDED.yandex_login,
                    email = EXCLUDED.email,
                    full_name = EXCLUDED.full_name,
                    raw_profile = EXCLUDED.raw_profile,
                    consent_state = EXCLUDED.consent_state,
                    consented_at = EXCLUDED.consented_at,
                    updated_at = now()
                """;
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
                .bind(0, userId)
                .bind(1, yandexAccountId);
        spec = bindOrNull(spec, 2, jwtAuth.getToken().getClaimAsString("yandex_login"));
        spec = bindOrNull(spec, 3, jwtAuth.getToken().getClaimAsString("email"));
        spec = bindOrNull(spec, 4, jwtAuth.getToken().getClaimAsString("yandex_full_name"));
        spec = bindOrNull(spec, 5, rawProfile);
        spec = bindOrNull(spec, 6, state);
        return spec
                .then()
                .thenReturn(ResponseEntity.ok(Map.of(
                        "consent", state,
                        "profile", profileClaims(jwtAuth))));
    }

    /** Биндит строковое значение или явный NULL (claim может отсутствовать). */
    private static DatabaseClient.GenericExecuteSpec bindOrNull(
            DatabaseClient.GenericExecuteSpec spec, int index, String value) {
        return value != null ? spec.bind(index, value) : spec.bindNull(index, String.class);
    }

    /**
     * Идентификатор пользователя из токена, пересланного BFF.
     * Gateway TokenRelay пересылает access-токен Keycloak: в нём нет claim {@code sub}
     * (он есть только в id_token), поэтому используем {@code preferred_username} —
     * уникальный username пользователя в realm.
     */
    private static String userIdOf(JwtAuthenticationToken jwtAuth) {
        String userId = jwtAuth.getToken().getClaimAsString("preferred_username");
        return userId != null ? userId : jwtAuth.getToken().getSubject();
    }

    // ------------------------------------------------------------------

    private Map<String, Object> pendingProfileResponse(JwtAuthenticationToken jwtAuth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("yandex", true);
        m.put("consent", "PENDING");
        m.put("consentedAt", null);
        m.put("profile", profileClaims(jwtAuth));
        return m;
    }

    private Map<String, Object> storedProfileResponse(Readable row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("yandex", true);
        m.put("consent", row.get("consent_state", String.class));
        m.put("consentedAt", row.get("consented_at", Instant.class));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("accountId", row.get("yandex_account_id", String.class));
        profile.put("login", row.get("yandex_login", String.class));
        profile.put("email", row.get("email", String.class));
        profile.put("fullName", row.get("full_name", String.class));
        m.put("profile", profile);
        return m;
    }

    /** Данные профиля Yandex из валидированного JWT (claim'ы, пришедшие из Yandex через Keycloak). */
    private Map<String, Object> profileClaims(JwtAuthenticationToken jwtAuth) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("accountId", jwtAuth.getToken().getClaimAsString("yandex_account_id"));
        profile.put("login", jwtAuth.getToken().getClaimAsString("yandex_login"));
        profile.put("email", jwtAuth.getToken().getClaimAsString("email"));
        profile.put("fullName", jwtAuth.getToken().getClaimAsString("yandex_full_name"));
        profile.put("name", jwtAuth.getToken().getClaimAsString("name"));
        profile.put("preferredUsername", jwtAuth.getToken().getClaimAsString("preferred_username"));
        return profile;
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
