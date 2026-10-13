package com.bionicpro.reportapi.controller;

import com.bionicpro.reportapi.model.ReportResponse;
import com.bionicpro.reportapi.service.CustomerNotFoundException;
import com.bionicpro.reportapi.service.EtlNotRunException;
import com.bionicpro.reportapi.service.NoDataException;
import com.bionicpro.reportapi.service.PeriodNotProcessedException;
import com.bionicpro.reportapi.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/reports?from=YYYY-MM-DD&to=YYYY-MM-DD
 *
 * Отчёт всегда по текущему пользователю: e-mail берётся из JWT,
 * выданный Keycloak, в параметрах запроса идентификатора пользователя нет.
 * Период не обязателен — по умолчанию весь обработанный ETL период.
 */
@RestController
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/api/reports")
    public Mono<ResponseEntity<Object>> report(
        @AuthenticationPrincipal JwtAuthenticationToken jwt,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to) {

        String emailClaim = jwt.getToken().getClaimAsString("email");
        String email = emailClaim != null
            ? emailClaim
            : jwt.getToken().getClaimAsString("preferred_username");
        if (email == null) {
            return fail(400, error("invalid_token", "Token does not contain user e-mail"));
        }

        LocalDate requestedFrom;
        LocalDate requestedTo;
        try {
            requestedFrom = from == null ? null : LocalDate.parse(from);
            requestedTo = to == null ? null : LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            return fail(400, error("invalid_date_format", "Use YYYY-MM-DD, e.g. 2026-09-01"));
        }

        return Mono
            .<Object>fromCallable(() -> service.buildReport(email, requestedFrom, requestedTo))
            .subscribeOn(Schedulers.boundedElastic())
            .map(ResponseEntity::ok)
            .onErrorResume(PeriodNotProcessedException.class, e -> fail(400,
                error("period_not_processed",
                    "ETL has processed data up to " + e.availableUntil()
                        + "; requested period includes unprocessed days",
                    "available_until", e.availableUntil().toString())))
            .onErrorResume(EtlNotRunException.class, e -> fail(503,
                error("etl_not_run", "ETL has not processed any data yet")))
            .onErrorResume(NoDataException.class, e -> fail(503,
                error("no_data", "Report view is empty, wait for the next ETL run")))
            .onErrorResume(CustomerNotFoundException.class, e -> fail(404,
                error("customer_not_found",
                    "You are not registered as a customer in CRM")));
    }

    private Mono<ResponseEntity<Object>> fail(int status, Map<String, Object> body) {
        return Mono.just(ResponseEntity.status(status).<Object>body(body));
    }

    private Map<String, Object> error(String code, String message, Object... kv) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            body.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return body;
    }
}
