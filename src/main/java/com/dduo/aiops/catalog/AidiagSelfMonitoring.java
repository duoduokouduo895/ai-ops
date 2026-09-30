package com.dduo.aiops.catalog;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps a small, failure-tolerant self-observability surface independent from the console UI.
 * Browser reads reuse this snapshot and re-evaluate freshness against the current clock; they must not
 * trigger diagnosis or retries.
 */
@Component
public class AidiagSelfMonitoring {

    static final String SNAPSHOT_SQL = """
            SELECT
              (SELECT count(*) FROM incident
                  WHERE status IN ('OPEN','DIAGNOSING','DIAGNOSED','FAILED')) AS active_incidents,
              (SELECT count(*) FROM diagnosis_run
                  WHERE status IN ('PENDING','LEASED','COLLECTING','EVIDENCE_READY','ANALYZING','VERIFYING',
                                   'GENERATING_REPORT','RETRY_WAIT')) AS pending_runs,
              (SELECT count(*) FROM diagnosis_run
                  WHERE status IN ('PENDING','RETRY_WAIT')) AS waiting_runs,
              (SELECT count(*) FROM diagnosis_run
                  WHERE status IN ('LEASED','COLLECTING','EVIDENCE_READY','ANALYZING','VERIFYING',
                                   'GENERATING_REPORT')) AS in_progress_runs,
              (SELECT min(created_at) FROM diagnosis_run
                  WHERE status IN ('PENDING','LEASED','COLLECTING','EVIDENCE_READY','ANALYZING','VERIFYING',
                                   'GENERATING_REPORT','RETRY_WAIT')) AS oldest_pending_at,
              (SELECT max(last_alert_at) FROM incident) AS last_alert_at,
              (SELECT max(collected_at) FROM evidence) AS last_evidence_at,
              (SELECT max(generated_at) FROM diagnosis_report) AS last_report_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ServiceCatalogRegistry catalogRegistry;
    private final Clock clock;
    private final Duration staleAfter;
    private final Duration backlogAfter;
    private final AtomicInteger catalogServices = new AtomicInteger();
    private final AtomicReference<CachedSnapshot> snapshot = new AtomicReference<>(CachedSnapshot.notSampled());

    public AidiagSelfMonitoring(JdbcTemplate jdbcTemplate, ServiceCatalogRegistry catalogRegistry,
            MeterRegistry meterRegistry, Environment environment, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogRegistry = catalogRegistry;
        this.clock = clock;
        this.staleAfter = Duration.ofSeconds(bounded(environment, "aidiag.self-monitor.stale-after-seconds", 90, 30, 900));
        this.backlogAfter = Duration.ofSeconds(bounded(environment, "aidiag.self-monitor.backlog-after-seconds", 600, 60, 3600));
        Gauge.builder("aidiag.catalog.services", catalogServices, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("aidiag.incidents.active", this, self -> nullable(self.current().activeIncidents()))
                .register(meterRegistry);
        Gauge.builder("aidiag.diagnosis.pending", this, self -> nullable(self.current().pendingRuns()))
                .register(meterRegistry);
        Gauge.builder("aidiag.diagnosis.backlog", this, self -> backlogGauge(self.current())).register(meterRegistry);
        Gauge.builder("aidiag.database.reachable", this, self -> databaseGauge(self.current().databaseStatus()))
                .register(meterRegistry);
        Gauge.builder("aidiag.pipeline.last.success.unixtime", this,
                self -> epochSeconds(self.current().lastSuccessfulCheckAt())).register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${aidiag.self-monitor.initial-delay-ms:15000}",
            fixedDelayString = "${aidiag.self-monitor.delay-ms:15000}")
    public void refresh() {
        var attemptedAt = clock.instant();
        try {
            catalogServices.set(catalogRegistry.snapshot().services().size());
        } catch (RuntimeException ignored) {
            // Catalog compilation is independent of database reachability.
        }
        var previous = snapshot.get();
        try {
            var row = jdbcTemplate.query(SNAPSHOT_SQL, resultSet -> {
                if (!resultSet.next()) {
                    throw new IllegalStateException("pipeline snapshot returned no row");
                }
                return new SnapshotRow(nullableLong(resultSet.getObject("active_incidents")),
                        nullableLong(resultSet.getObject("pending_runs")),
                        nullableLong(resultSet.getObject("waiting_runs")),
                        nullableLong(resultSet.getObject("in_progress_runs")),
                        instant(resultSet.getTimestamp("oldest_pending_at")),
                        instant(resultSet.getTimestamp("last_alert_at")),
                        instant(resultSet.getTimestamp("last_evidence_at")),
                        instant(resultSet.getTimestamp("last_report_at")));
            });
            if (row == null) {
                throw new IllegalStateException("pipeline snapshot returned no row");
            }
            snapshot.set(CachedSnapshot.success(attemptedAt, row));
        } catch (RuntimeException exception) {
            snapshot.set(previous.failedAttempt(attemptedAt));
        }
    }

    public PipelineHealthView current() {
        return snapshot.get().viewedAt(clock.instant(), staleAfter, backlogAfter);
    }

    private static int bounded(Environment environment, String property, int fallback, int minimum, int maximum) {
        var value = environment.getProperty(property, Integer.class, fallback);
        if (value == null || value < minimum || value > maximum) {
            throw new IllegalArgumentException(property + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static double nullable(Long value) {
        return value == null ? Double.NaN : value.doubleValue();
    }

    private static double databaseGauge(CatalogHealthService.HealthState status) {
        if (status == CatalogHealthService.HealthState.UP) {
            return 1.0d;
        }
        if (status == CatalogHealthService.HealthState.DOWN) {
            return 0.0d;
        }
        return Double.NaN;
    }

    private static double backlogGauge(PipelineHealthView view) {
        if ("BACKLOG".equals(view.pipelineStatus())) {
            return 1.0d;
        }
        if ("IDLE".equals(view.pipelineStatus()) || "IN_PROGRESS".equals(view.pipelineStatus())) {
            return 0.0d;
        }
        return Double.NaN;
    }

    private static double epochSeconds(Instant instant) {
        return instant == null ? Double.NaN : instant.getEpochSecond();
    }

    record SnapshotRow(Long activeIncidents, Long pendingRuns, Long waitingRuns, Long inProgressRuns,
            Instant oldestPendingAt, Instant lastAlertAt, Instant lastEvidenceAt, Instant lastReportAt) { }

    record CachedSnapshot(Instant lastAttemptAt, boolean lastAttemptSucceeded, Instant lastSuccessfulCheckAt,
            SnapshotRow row) {

        static CachedSnapshot notSampled() {
            return new CachedSnapshot(null, false, null, null);
        }

        static CachedSnapshot success(Instant attemptedAt, SnapshotRow row) {
            return new CachedSnapshot(attemptedAt, true, attemptedAt, row);
        }

        CachedSnapshot failedAttempt(Instant attemptedAt) {
            return new CachedSnapshot(attemptedAt, false, lastSuccessfulCheckAt, row);
        }

        PipelineHealthView viewedAt(Instant now, Duration staleAfter, Duration backlogAfter) {
            if (lastAttemptAt == null) {
                return PipelineHealthView.notSampled(now);
            }
            var attemptExpired = lastAttemptAt.isBefore(now.minus(staleAfter));
            if (attemptExpired) {
                var lastKnown = row;
                return new PipelineHealthView(now, lastSuccessfulCheckAt, "STALE", "SNAPSHOT_EXPIRED",
                        CatalogHealthService.HealthState.UNKNOWN, null, null, null, null, null,
                        lastKnown == null ? null : lastKnown.lastAlertAt(),
                        lastKnown == null ? null : lastKnown.lastEvidenceAt(),
                        lastKnown == null ? null : lastKnown.lastReportAt(), "SNAPSHOT_EXPIRED");
            }
            if (!lastAttemptSucceeded || row == null) {
                return new PipelineHealthView(now, lastSuccessfulCheckAt, "FRESH", "QUERY_FAILED",
                        CatalogHealthService.HealthState.DOWN, null, null, null, null, null, null, null, null,
                        "QUERY_FAILED");
            }
            Long oldestAge = null;
            if (row.pendingRuns() != null && row.pendingRuns() > 0 && row.oldestPendingAt() != null) {
                oldestAge = Math.max(0, Duration.between(row.oldestPendingAt(), now).getSeconds());
            }
            var pipelineStatus = "IDLE";
            if (row.pendingRuns() != null && row.pendingRuns() > 0) {
                pipelineStatus = oldestAge != null && oldestAge >= backlogAfter.getSeconds() ? "BACKLOG" : "IN_PROGRESS";
            }
            return new PipelineHealthView(now, lastSuccessfulCheckAt, "FRESH", "FRESH",
                    CatalogHealthService.HealthState.UP, row.activeIncidents(), row.pendingRuns(), row.waitingRuns(),
                    row.inProgressRuns(), oldestAge, row.lastAlertAt(), row.lastEvidenceAt(), row.lastReportAt(),
                    pipelineStatus);
        }
    }

    public record PipelineHealthView(Instant queriedAt, Instant lastSuccessfulCheckAt, String freshness, String reason,
            CatalogHealthService.HealthState databaseStatus, Long activeIncidents, Long pendingRuns, Long waitingRuns,
            Long inProgressRuns, Long oldestPendingAgeSeconds, Instant lastAlertAt, Instant lastEvidenceAt,
            Instant lastReportAt, String pipelineStatus) {

        public static PipelineHealthView notSampled(Instant queriedAt) {
            return new PipelineHealthView(queriedAt, null, "UNKNOWN", "NOT_SAMPLED",
                    CatalogHealthService.HealthState.UNKNOWN, null, null, null, null, null, null, null, null,
                    "NOT_SAMPLED");
        }

        public static PipelineHealthView notSampled() {
            return notSampled(null);
        }
    }
}
