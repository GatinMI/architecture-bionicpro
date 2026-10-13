package com.bionicpro.reportapi.service;

import com.bionicpro.reportapi.ch.ClickHouseReportRepository;
import com.bionicpro.reportapi.ch.ProsthesisDayRow;
import com.bionicpro.reportapi.model.Customer;
import com.bionicpro.reportapi.model.DailyMetrics;
import com.bionicpro.reportapi.model.ProsthesisReport;
import com.bionicpro.reportapi.model.ProsthesisSummary;
import com.bionicpro.reportapi.model.ReportResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Сборка отчёта по текущему пользователю из витрины ClickHouse.
 *
 * Доступ «только к себе» обеспечен по конструкции: e-mail берётся из
 * JWT, выданный Keycloak, в параметрах запроса пользователя нет.
 */
@Service
public class ReportService {

    private final ClickHouseReportRepository repo;

    public ReportService(ClickHouseReportRepository repo) {
        this.repo = repo;
    }

    public ReportResponse buildReport(String email,
                                      LocalDate requestedFrom,
                                      LocalDate requestedTo) {
        LocalDate watermark = repo.watermarkUpTo().orElseThrow(EtlNotRunException::new);
        LocalDate minDay = repo.minViewDay().orElseThrow(NoDataException::new);

        LocalDate from = requestedFrom != null ? requestedFrom : minDay;
        LocalDate to = requestedTo != null ? requestedTo : watermark;

        // Данных за период, который ещё не обработал Airflow, нет — не отдаём
        if (from.isAfter(watermark) || to.isAfter(watermark)) {
            throw new PeriodNotProcessedException(watermark);
        }
        // Период, раньше которого телеметрия не собиралась — отрезаем
        if (from.isBefore(minDay)) {
            from = minDay;
        }

        Customer customer = repo.findCustomerByEmail(email)
            .orElseThrow(() -> new CustomerNotFoundException(email));

        List<ProsthesisDayRow> rows = repo.findDailyByProsthesis(email, from, to);
        return new ReportResponse(
            customer,
            new ReportResponse.RequestedPeriod(requestedFrom, requestedTo),
            new ReportResponse.AppliedPeriod(from, to),
            watermark,
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
}
