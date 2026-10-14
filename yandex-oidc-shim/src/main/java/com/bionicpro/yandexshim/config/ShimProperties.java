package com.bionicpro.yandexshim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "yandex.shim")
public class ShimProperties {

    /** Client-id expected in incoming requests from the Keycloak broker (also used as the {@code aud} of the id_token). */
    private String clientId = "bionicpro";

    /** Client-secret shared with the Keycloak broker (verified on /token). */
    private String clientSecret = "bionicpro-yandex-shim-secret";

    /** Issuer (iss) of the id_token minted by the shim; must match the "issuer" field of the Keycloak broker. */
    private String issuer = "http://localhost:8090";

    /** Client-id of the Yandex ID app (created by the user on oauth.yandex.ru). */
    private String yandexClientId;

    /** Client-secret of the Yandex ID app. */
    private String yandexClientSecret;

    /** Scopes requested from Yandex (e.g. "login:info login:email"). Must match the rights of the Yandex app. */
    private String yandexScopes = "login:info login:email";

    private String yandexAuthorizeUrl = "https://oauth.yandex.ru/authorize";
    private String yandexTokenUrl = "https://oauth.yandex.ru/token";
    private String yandexInfoUrl = "https://login.yandex.ru/info";

    /** TTL of the synthesized id_token (seconds). */
    private int idTokenTtlSeconds = 300;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getYandexClientId() {
        return yandexClientId;
    }

    public void setYandexClientId(String yandexClientId) {
        this.yandexClientId = yandexClientId;
    }

    public String getYandexClientSecret() {
        return yandexClientSecret;
    }

    public void setYandexClientSecret(String yandexClientSecret) {
        this.yandexClientSecret = yandexClientSecret;
    }

    public String getYandexScopes() {
        return yandexScopes;
    }

    public void setYandexScopes(String yandexScopes) {
        this.yandexScopes = yandexScopes;
    }

    public String getYandexAuthorizeUrl() {
        return yandexAuthorizeUrl;
    }

    public void setYandexAuthorizeUrl(String yandexAuthorizeUrl) {
        this.yandexAuthorizeUrl = yandexAuthorizeUrl;
    }

    public String getYandexTokenUrl() {
        return yandexTokenUrl;
    }

    public void setYandexTokenUrl(String yandexTokenUrl) {
        this.yandexTokenUrl = yandexTokenUrl;
    }

    public String getYandexInfoUrl() {
        return yandexInfoUrl;
    }

    public void setYandexInfoUrl(String yandexInfoUrl) {
        this.yandexInfoUrl = yandexInfoUrl;
    }

    public int getIdTokenTtlSeconds() {
        return idTokenTtlSeconds;
    }

    public void setIdTokenTtlSeconds(int idTokenTtlSeconds) {
        this.idTokenTtlSeconds = idTokenTtlSeconds;
    }
}