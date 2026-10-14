package com.bionicpro.auth.filter;


import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;


public class SessionRotationWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(
        ServerWebExchange exchange,
        WebFilterChain chain) {

        String path = exchange.getRequest().getPath().value();

        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        return exchange.getPrincipal()
            .ofType(Authentication.class)
            .filter(authentication ->
                        authentication.isAuthenticated()
                            && !(authentication instanceof AnonymousAuthenticationToken))
            .flatMap(_ ->
                         exchange.getSession()
                             .flatMap(WebSession::changeSessionId))
            .then(chain.filter(exchange));
    }
}