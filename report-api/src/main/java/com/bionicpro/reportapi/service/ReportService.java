package com.bionicpro.reportapi.service;

import com.bionicpro.reportapi.ch.ClickHouseReportRepository;
import com.bionicpro.reportapi.ch.ProsthesisDayRow;
import com.bionicpro.reportapi.model.Customer;
import com.bionicpro.reportapi.model.DailyMetrics;
import com.bionicpro.reportapi.model.ProsthesisReport;
import com.bionicpro.reportapi.model.ProsthesisSummary;
import com.bionicpro.reportapi.model.ReportEnvelope;
import com.bionicpro.reportapi.model.ReportResponse;
import com.bionicpro.reportapi.s3.ReportKey;
import com.bionicpro.reportapi.s3.S3ReportStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Service
public class ReportService {

    private final ClickHouseReportRepository repo;
    private final S3ReportStore store;
    private final ObjectMapper mapper;

    public ReportService(ClickHouseReportRepository repo, S3ReportStore store, ObjectMapper mapper) {
        this.repo = repo;
        this.store = store;
        this.mapper = mapper;
    }

    public Mono<ReportEnvelope> getReport(String email,
                                          LocalDate requestedFrom,
                                          LocalDate requestedTo) {
        return Mono
            .fromCallable(() -> prepare(email, requestedFrom, requestedTo))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(prepared -> store.exists(prepared.key().value())
                .flatMap(present -> present
                    ? Mono.just(toEnvelope(prepared, true))
                    : generateAndStore(prepared)));
    }

    private Mono<ReportEnvelope> generateAndStore(PreparedReport prepared) {
        return Mono
            .fromCallable(() -> buildReportBody(prepared))
            .subscribeOn(Schedulers.boundedElastic())
            .map(this::toJson)
            .flatMap(bytes -> store.put(prepared.key().value(), bytes)
                .thenReturn(toEnvelope(prepared, false)));
    }

    private ReportEnvelope toEnvelope(PreparedReport prepared, boolean cached) {
        return new ReportEnvelope(
            store.cdnUrl(prepared.key().value()),
            prepared.requestedFrom(),
            prepared.requestedTo(),
            prepared.watermark(),
            cached
        );
    }

    private byte[] toJson(ReportResponse report) {
        try {
            return mapper.writeValueAsBytes(report);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize report", e);
        }
    }


    private PreparedReport prepare(String email,
                                   LocalDate requestedFrom,
                                   LocalDate requestedTo) {
        LocalDate watermark = repo.watermarkUpTo().orElseThrow(EtlNotRunException::new);

        // Данных за период, который ещё не обработал Airflow, нет — не отдаём
        if ((requestedFrom != null && requestedFrom.isAfter(watermark))
            || (requestedTo != null && requestedTo.isAfter(watermark))) {
            throw new PeriodNotProcessedException(watermark);
        }

        return new PreparedReport(
            email,
            requestedFrom,
            requestedTo,
            watermark,
            ReportKey.of(email, watermark, requestedFrom, requestedTo)
        );
    }

    private ReportResponse buildReportBody(PreparedReport prepared) {
        LocalDate minDay = repo.minViewDay().orElseThrow(NoDataException::new);
        LocalDate from = prepared.requestedFrom() != null ? prepared.requestedFrom() : minDay;
        LocalDate to = prepared.requestedTo() != null ? prepared.requestedTo() : prepared.watermark();

        // Период, раньше которого телеметрия не собиралась — отрезаем
        if (from.isBefore(minDay)) {
            from = minDay;
        }

        Customer customer = repo.findCustomerByEmail(prepared.email())
            .orElseThrow(() -> new CustomerNotFoundException(prepared.email()));

        List<ProsthesisDayRow> rows = repo.findDailyByProsthesis(customer.id(), from, to);
        return new ReportResponse(
            customer,
            new ReportResponse.RequestedPeriod(prepared.requestedFrom(), prepared.requestedTo()),
            new ReportResponse.AppliedPeriod(from, to),
            prepared.watermark(),
            toProsthesisReports(rows)
        );
    }

    private List<ProsthesisReport> toProsthesisReports(List<ProsthesisDayRow> rows) {
        Map<Integer, List<ProsthesisDayRow>> byProsthesis = new LinkedHashMap<>();
        for (ProsthesisDayRow row : rows) {
            byProsthesis.computeIfAbsent(row.prosthesisId(), k -> new ArrayList<>()).add(row);
        }

        List<ProsthesisReport> result = new ArrayList<>();
        byProsthesis.forEach((id, prosthesisRows) -> {
            List<DailyMetrics> daily = prosthesisRows.stream().map(ProsthesisDayRow::day).toList();
            result.add(new ProsthesisReport(
                id,
                prosthesisRows.get(0).serial(),
                prosthesisRows.get(0).model(),
                summarize(daily),
                daily
            ));
        });
        return result;
    }

    private ProsthesisSummary summarize(List<DailyMetrics> daily) {
        if (daily.isEmpty()) {
            return new ProsthesisSummary(0, 0, 0, 0, 0, 0, 0, List.of());
        }
        long movements = 0;
        long gestures = 0;
        long errors = 0;
        float maxTemp = 0;
        float batterySum = 0;
        float qualitySum = 0;
        TreeSet<String> codes = new TreeSet<>();
        for (DailyMetrics d : daily) {
            movements += d.totalMovements();
            gestures += d.totalGestures();
            errors += d.totalRecognitionErrors();
            maxTemp = Math.max(maxTemp, d.maxTemperatureC());
            batterySum += d.avgBatteryLevel();
            qualitySum += d.avgSignalQuality();
            codes.addAll(d.errorCodes());
        }
        long attempts = gestures + errors;
        float errorRate = attempts > 0 ? (float) errors / attempts : 0;
        return new ProsthesisSummary(
            daily.size(),
            batterySum / daily.size(),
            qualitySum / daily.size(),
            movements,
            gestures,
            errorRate,
            maxTemp,
            List.copyOf(codes)
        );
    }


    private record PreparedReport(
        String email,
        LocalDate requestedFrom,
        LocalDate requestedTo,
        LocalDate watermark,
        ReportKey key) {
    }
}
