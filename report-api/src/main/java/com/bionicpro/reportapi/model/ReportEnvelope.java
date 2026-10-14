package com.bionicpro.reportapi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportEnvelope(
    @JsonProperty("report_url") String reportUrl,
    LocalDate from,
    LocalDate to,
    @JsonProperty("data_available_until") LocalDate dataAvailableUntil,
    boolean cached) {
}
