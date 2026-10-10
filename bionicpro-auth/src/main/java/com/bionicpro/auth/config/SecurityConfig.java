package com.bionicpro.auth.config;


import com.bionicpro.auth.filter.SessionRotationWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {



    @Bean
    ServerLogoutSuccessHandler oidcLogoutSuccessHandler(
        ReactiveClientRegistrationRepository clientRegistrationRepository) {

        var handler = new OidcClientInitiatedServerLogoutSuccessHandler(
            clientRegistrationRepository
        );

        handler.setPostLogoutRedirectUri("{baseUrl}/");

        return handler;
    }

    @Bean
    ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
        ReactiveClientRegistrationRepository registrations,
        ServerOAuth2AuthorizedClientRepository authorizedClients) {

        var provider =
            ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken()
                .build();

        var manager = new DefaultReactiveOAuth2AuthorizedClientManager(
            registrations,
            authorizedClients
        );

        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    ServerOAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new WebSessionServerOAuth2AuthorizedClientRepository();
    }




    @Bean
    SecurityWebFilterChain securityWebFilterChain(
        ServerHttpSecurity http,
        ServerOAuth2AuthorizedClientRepository authorizedClients,
        ServerLogoutSuccessHandler oidcLogoutSuccessHandler) {

        return http
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/oauth2/**").permitAll()
                .anyExchange().authenticated()
            )
            .csrf(csrf -> csrf.requireCsrfProtectionMatcher(new AndServerWebExchangeMatcher(
                CsrfWebFilter.DEFAULT_CSRF_MATCHER,
                // POST-эндпоинты, вызываемые SPA со стороны одного origin:
                // защищаются httpOnly-кукой с SameSite=Lax (кросс-сайт POST блокируется браузером),
                // CSRF-токен для них не требуется
                new NegatedServerWebExchangeMatcher(
                    ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST,
                        "/logout", "/api/users/me/yandex-consent")))))
            .oauth2Login(oauth2 -> oauth2
                .authorizedClientRepository(authorizedClients)
            )
            .oauth2Client(oauth2 -> oauth2
                .authorizedClientRepository(authorizedClients)
            )
            .logout(logout -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler)
            )
            .addFilterAfter(new SessionRotationWebFilter(),
                            SecurityWebFiltersOrder.SECURITY_CONTEXT_SERVER_WEB_EXCHANGE)
            .build();
    }
}
