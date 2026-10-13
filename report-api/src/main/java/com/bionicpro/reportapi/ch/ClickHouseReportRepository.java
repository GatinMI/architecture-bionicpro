package com.bionicpro.reportapi.ch;

import com.bionicpro.reportapi.model.Customer;
import com.bionicpro.reportapi.model.DailyMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Чтение витрины отчётности из ClickHouse (OLAP).
 *
 * Пула соединений нет: запросы редкие и короткие, на время каждого
 * запроса открывается отдельное соединение (HTTP-протокол ClickHouse).
 * Блокирующий JDBC вызывающий код оборачивает в boundedElastic.
 */
@Repository
public class ClickHouseReportRepository {

    private final String url;
    private final String user;
    private final String password;

    public ClickHouseReportRepository(
        @Value("${clickhouse.url}") String url,
        @Value("${clickhouse.user}") String user,
        @Value("${clickhouse.password}") String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** До какой даты Airflow обработал данные (последний watermark). */
    public Optional<LocalDate> watermarkUpTo() {
        String sql = "SELECT processed_up_to FROM etl_watermark FINAL "
            + "ORDER BY run_id DESC LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getDate(1).toLocalDate()) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("ClickHouse unavailable", e);
        }
    }

    /** Самая ранняя дата в витрине (данные раньше — не собирались). */
    public Optional<LocalDate> minViewDay() {
        String sql = "SELECT min(day) FROM report_prosthesis_daily";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && !rs.wasNull()
                ? Optional.of(rs.getDate(1).toLocalDate())
                : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("ClickHouse unavailable", e);
        }
    }

    public Optional<Customer> findCustomerByEmail(String email) {
        String sql = "SELECT id, full_name, email FROM crm_customers WHERE email = ? LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Customer(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ClickHouse unavailable", e);
        }
    }

    /** Подневные агрегаты по всем протезам пользователя за период. */
    public List<ProsthesisDayRow> findDailyByProsthesis(String email, LocalDate from, LocalDate to) {
        String sql = """
            SELECT prosthesis_id, prosthesis_serial, prosthesis_model, day,
                   avg_battery_level, min_battery_level, avg_signal_quality,
                   total_movements, total_gestures, total_recognition_errors,
                   max_temperature_c, error_codes, measurements
            FROM report_prosthesis_daily
            WHERE customer_email = ? AND day >= ? AND day <= ?
            ORDER BY prosthesis_id, day
            """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setDate(2, Date.valueOf(from));
            ps.setDate(3, Date.valueOf(to));
            List<ProsthesisDayRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ProsthesisDayRow(
                        rs.getInt("prosthesis_id"),
                        rs.getString("prosthesis_serial"),
                        rs.getString("prosthesis_model"),
                        new DailyMetrics(
                            rs.getDate("day").toLocalDate(),
                            rs.getFloat("avg_battery_level"),
                            rs.getFloat("min_battery_level"),
                            rs.getFloat("avg_signal_quality"),
                            rs.getLong("total_movements"),
                            rs.getLong("total_gestures"),
                            rs.getLong("total_recognition_errors"),
                            rs.getFloat("max_temperature_c"),
                            toStringList(rs.getArray("error_codes").getArray()),
                            rs.getInt("measurements")
                        )
                    ));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("ClickHouse unavailable", e);
        }
    }

    private List<String> toStringList(Object arrayValue) {
        if (arrayValue instanceof String[] s) {
            return List.of(s);
        }
        if (arrayValue instanceof Object[] o) {
            List<String> out = new ArrayList<>();
            for (Object v : o) {
                out.add(String.valueOf(v));
            }
            return out;
        }
        if (arrayValue instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
