package com.bionicpro.userapi.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@RestController
public class UserController {

    @GetMapping("/api/users/me")
    public Mono<Map<String, Object>> me(@AuthenticationPrincipal JwtAuthenticationToken jwtAuth) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("userId", jwtAuth.getToken().getSubject());
        body.put("username", jwtAuth.getToken().getClaimAsString("preferred_username"));
        body.put("email", jwtAuth.getToken().getClaimAsString("email"));
        body.put("roles", realmRoles(jwtAuth));
        return Mono.just(body);
    }

    @SuppressWarnings("unchecked")
    private List<String> realmRoles(JwtAuthenticationToken jwtAuth) {
        Object realmAccess = jwtAuth.getToken().getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> realm) {
            Object roles = realm.get("roles");
            if (roles instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
        }
        return List.of();
    }
}
