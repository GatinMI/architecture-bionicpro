package com.bionicpro.reportapi.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/** Полный отчёт: клиент, применённый период и отчёты по его протезам. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportResponse(
    Customer customer,
    RequestedPeriod requestedPeriod,
    AppliedPeriod period,
    LocalDate dataAvailableUntil,
    List<ProsthesisReport> prostheses) {

    /** Период, запрошенный пользователем (может быть частично неприменим). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestedPeriod(LocalDate from, LocalDate to) {
    }

    /** Период, по которому фактически сформирован отчёт. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppliedPeriod(LocalDate from, LocalDate to) {
    }
}
