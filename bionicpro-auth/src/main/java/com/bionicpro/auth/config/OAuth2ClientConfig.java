package com.bionicpro.auth.config;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bean из автоконфигурации не подошел, так как наш бэкенд с keycloak общается по внутреннему адресу, а issuerUri выдается на
 * внешнем адресе, который не доступен изнутри кластера. Bean из автоконфигурации пытался использовать issuerUri.
 */
@Configuration
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class OAuth2ClientConfig {

    @Bean
    ReactiveClientRegistrationRepository clientRegistrationRepository(
        OAuth2ClientProperties properties) {
        List<ClientRegistration> registrations = new ArrayList<>();
        properties.getRegistration()
            .forEach((id, reg) -> {
                OAuth2ClientProperties.Provider provider = properties.getProvider().get(reg.getProvider());
                registrations.add(
                    ClientRegistration.withRegistrationId(id)
                        .clientId(reg.getClientId())
                        .clientSecret(reg.getClientSecret())
                        .authorizationGrantType(
                            new AuthorizationGrantType(reg.getAuthorizationGrantType()))
                        .scope(reg.getScope())
                        .redirectUri(reg.getRedirectUri())
                        .issuerUri(provider.getIssuerUri())
                        .authorizationUri(provider.getAuthorizationUri())
                        .tokenUri(provider.getTokenUri())
                        .jwkSetUri(provider.getJwkSetUri())
                        .userInfoUri(provider.getUserInfoUri())
                        .userNameAttributeName("preferred_username")
                        .clientName("Keycloak")
                        .providerConfigurationMetadata(Map.of(
                            "end_session_endpoint",
                            provider.getIssuerUri() + "/protocol/openid-connect/logout"
                        ))
                        .build());
            });



        return new InMemoryReactiveClientRegistrationRepository(registrations);
    }
}
