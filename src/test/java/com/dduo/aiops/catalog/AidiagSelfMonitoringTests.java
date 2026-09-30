package com.dduo.aiops.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.env.MockEnvironment;

class AidiagSelfMonitoringTests {

    private static final Instant T0 = Instant.parse("2026-09-10T06:00:00Z");
    private final ServiceCatalogRegistry catalogRegistry = mock(ServiceCatalogRegistry.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(T0);

    @Test
    void initialSnapshotIsUnknownRatherThanAFakeZero() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        var monitoring = monitoring();

        var view = monitoring.current();
        assertThat(view.pipelineStatus()).isEqualTo("NOT_SAMPLED");
        assertThat(view.pendingRuns()).isNull();
        assertThat(view.activeIncidents()).isNull();
        assertThat(view.oldestPendingAgeSeconds()).isNull();
        assertThat(view.databaseStatus()).isEqualTo(CatalogHealthService.HealthState.UNKNOWN);
        assertThat(gauge("aidiag.diagnosis.pending")).isNaN();
        assertThat(gauge("aidiag.database.reachable")).isNaN();
        assertThat(gauge("aidiag.diagnosis.backlog")).isNaN();
        assertThat(AidiagSelfMonitoring.SNAPSHOT_SQL).contains("waiting_runs", "in_progress_runs", "oldest_pending_at");
    }

    @Test
    void idleSuccessHasNullOldestAge() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        when(jdbcTemplate.query(eq(AidiagSelfMonitoring.SNAPSHOT_SQL), any(ResultSetExtractor.class)))
                .thenReturn(row(0L, 0L, 0L, 0L, null, T0.minusSeconds(3600), T0.minusSeconds(60), T0.minusSeconds(30)));
        var monitoring = monitoring();
        monitoring.refresh();

        var view = monitoring.current();
        assertThat(view.pipelineStatus()).isEqualTo("IDLE");
        assertThat(view.pendingRuns()).isZero();
        assertThat(view.oldestPendingAgeSeconds()).isNull();
        assertThat(view.databaseStatus()).isEqualTo(CatalogHealthService.HealthState.UP);
        assertThat(view.lastSuccessfulCheckAt()).isEqualTo(T0);
        assertThat(view.queriedAt()).isEqualTo(T0);
        assertThat(gauge("aidiag.diagnosis.pending")).isEqualTo(0.0d);
        assertThat(gauge("aidiag.diagnosis.backlog")).isEqualTo(0.0d);
        assertThat(gauge("aidiag.database.reachable")).isEqualTo(1.0d);
    }

    @Test
    void shortPendingWorkIsInProgressNotBacklog() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        when(jdbcTemplate.query(eq(AidiagSelfMonitoring.SNAPSHOT_SQL), any(ResultSetExtractor.class)))
                .thenReturn(row(1L, 1L, 0L, 1L, T0.minusSeconds(30), null, null, null));
        var monitoring = monitoring();
        monitoring.refresh();

        var view = monitoring.current();
        assertThat(view.pipelineStatus()).isEqualTo("IN_PROGRESS");
        assertThat(view.pendingRuns()).isEqualTo(1L);
        assertThat(view.inProgressRuns()).isEqualTo(1L);
        assertThat(view.oldestPendingAgeSeconds()).isEqualTo(30L);
        assertThat(gauge("aidiag.diagnosis.backlog")).isEqualTo(0.0d);
        assertThat(gauge("aidiag.diagnosis.pending")).isEqualTo(1.0d);
    }

    @Test
    void persistentPendingWorkIsBacklog() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        when(jdbcTemplate.query(eq(AidiagSelfMonitoring.SNAPSHOT_SQL), any(ResultSetExtractor.class)))
                .thenReturn(row(2L, 3L, 3L, 0L, T0.minusSeconds(120), null, null, null));
        var monitoring = monitoring(90, 60);
        monitoring.refresh();

        var view = monitoring.current();
        assertThat(view.pipelineStatus()).isEqualTo("BACKLOG");
        assertThat(view.pendingRuns()).isEqualTo(3L);
        assertThat(view.oldestPendingAgeSeconds()).isEqualTo(120L);
        assertThat(gauge("aidiag.diagnosis.backlog")).isEqualTo(1.0d);
    }

    @Test
    void stoppedRefreshExpiresTheCachedSuccessWithoutPretendingToQuery() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        when(jdbcTemplate.query(eq(AidiagSelfMonitoring.SNAPSHOT_SQL), any(ResultSetExtractor.class)))
                .thenReturn(row(0L, 0L, 0L, 0L, null, T0, T0, T0));
        var monitoring = monitoring();
        monitoring.refresh();
        assertThat(monitoring.current().pipelineStatus()).isEqualTo("IDLE");
        assertThat(monitoring.current().freshness()).isEqualTo("FRESH");

        clock.set(T0.plusSeconds(3600));
        var expired = monitoring.current();
        assertThat(expired.pipelineStatus()).isEqualTo("SNAPSHOT_EXPIRED");
        assertThat(expired.freshness()).isEqualTo("STALE");
        assertThat(expired.reason()).isEqualTo("SNAPSHOT_EXPIRED");
        assertThat(expired.pendingRuns()).isNull();
        assertThat(expired.activeIncidents()).isNull();
        assertThat(expired.databaseStatus()).isEqualTo(CatalogHealthService.HealthState.UNKNOWN);
        assertThat(expired.lastSuccessfulCheckAt()).isEqualTo(T0);
        assertThat(expired.queriedAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(gauge("aidiag.diagnosis.pending")).isNaN();
        assertThat(gauge("aidiag.database.reachable")).isNaN();
        assertThat(gauge("aidiag.diagnosis.backlog")).isNaN();
        assertThat(gauge("aidiag.pipeline.last.success.unixtime")).isEqualTo((double) T0.getEpochSecond());
    }

    @Test
    void ongoingDatabaseFailuresStayQueryFailedAfterLastSuccessExpires() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        when(jdbcTemplate.query(eq(AidiagSelfMonitoring.SNAPSHOT_SQL), any(ResultSetExtractor.class)))
                .thenReturn(row(0L, 0L, 0L, 0L, null, T0, T0, T0))
                .thenThrow(new DataRetrievalFailureException("database"));
        var monitoring = monitoring();
        monitoring.refresh();
        assertThat(monitoring.current().pipelineStatus()).isEqualTo("IDLE");

        for (int elapsed = 15; elapsed <= 180; elapsed += 15) {
            clock.set(T0.plusSeconds(elapsed));
            monitoring.refresh();
            var view = monitoring.current();
            assertThat(view.pipelineStatus()).isEqualTo("QUERY_FAILED");
            assertThat(view.reason()).isEqualTo("QUERY_FAILED");
            assertThat(view.databaseStatus()).isEqualTo(CatalogHealthService.HealthState.DOWN);
            assertThat(view.pendingRuns()).isNull();
            assertThat(view.lastSuccessfulCheckAt()).isEqualTo(T0);
            assertThat(gauge("aidiag.database.reachable")).isEqualTo(0.0d);
            assertThat(gauge("aidiag.diagnosis.pending")).isNaN();
        }
    }

    @Test
    void firstFailureThenStoppedRefreshBecomesSnapshotExpired() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        when(jdbcTemplate.query(eq(AidiagSelfMonitoring.SNAPSHOT_SQL), any(ResultSetExtractor.class)))
                .thenThrow(new DataRetrievalFailureException("database"));
        var monitoring = monitoring();
        monitoring.refresh();
        assertThat(monitoring.current().pipelineStatus()).isEqualTo("QUERY_FAILED");
        assertThat(monitoring.current().databaseStatus()).isEqualTo(CatalogHealthService.HealthState.DOWN);
        assertThat(gauge("aidiag.database.reachable")).isEqualTo(0.0d);

        clock.set(T0.plusSeconds(3600));
        var expired = monitoring.current();
        assertThat(expired.pipelineStatus()).isEqualTo("SNAPSHOT_EXPIRED");
        assertThat(expired.databaseStatus()).isEqualTo(CatalogHealthService.HealthState.UNKNOWN);
        assertThat(expired.lastSuccessfulCheckAt()).isNull();
        assertThat(gauge("aidiag.database.reachable")).isNaN();
    }

    @Test
    void databaseFailureClearsCountsAndKeepsLastSuccess() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        when(jdbcTemplate.query(eq(AidiagSelfMonitoring.SNAPSHOT_SQL), any(ResultSetExtractor.class)))
                .thenReturn(row(0L, 0L, 0L, 0L, null, null, null, null))
                .thenThrow(new DataRetrievalFailureException("database"));
        var monitoring = monitoring();
        monitoring.refresh();
        assertThat(monitoring.current().pipelineStatus()).isEqualTo("IDLE");

        monitoring.refresh();
        var failed = monitoring.current();
        assertThat(failed.pipelineStatus()).isEqualTo("QUERY_FAILED");
        assertThat(failed.reason()).isEqualTo("QUERY_FAILED");
        assertThat(failed.pendingRuns()).isNull();
        assertThat(failed.databaseStatus()).isEqualTo(CatalogHealthService.HealthState.DOWN);
        assertThat(failed.lastSuccessfulCheckAt()).isEqualTo(T0);
        assertThat(gauge("aidiag.diagnosis.pending")).isNaN();
        assertThat(gauge("aidiag.database.reachable")).isEqualTo(0.0d);
    }

    @Test
    void recoveryAfterFailureRestoresCounts() {
        when(catalogRegistry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, T0, "digest", List.of()));
        when(jdbcTemplate.query(eq(AidiagSelfMonitoring.SNAPSHOT_SQL), any(ResultSetExtractor.class)))
                .thenThrow(new DataRetrievalFailureException("database"))
                .thenReturn(row(1L, 0L, 0L, 0L, null, T0, T0, T0));
        var monitoring = monitoring();
        monitoring.refresh();
        assertThat(monitoring.current().pipelineStatus()).isEqualTo("QUERY_FAILED");
        assertThat(monitoring.current().lastSuccessfulCheckAt()).isNull();

        monitoring.refresh();
        assertThat(monitoring.current().pipelineStatus()).isEqualTo("IDLE");
        assertThat(monitoring.current().pendingRuns()).isZero();
        assertThat(monitoring.current().lastSuccessfulCheckAt()).isEqualTo(T0);
        assertThat(gauge("aidiag.database.reachable")).isEqualTo(1.0d);
    }

    private static AidiagSelfMonitoring.SnapshotRow row(Long active, Long pending, Long waiting, Long inProgress,
            Instant oldestPendingAt, Instant lastAlertAt, Instant lastEvidenceAt, Instant lastReportAt) {
        return new AidiagSelfMonitoring.SnapshotRow(active, pending, waiting, inProgress, oldestPendingAt, lastAlertAt,
                lastEvidenceAt, lastReportAt);
    }

    private AidiagSelfMonitoring monitoring() {
        return monitoring(90, 600);
    }

    private AidiagSelfMonitoring monitoring(int staleAfterSeconds, int backlogAfterSeconds) {
        var environment = new MockEnvironment()
                .withProperty("aidiag.self-monitor.stale-after-seconds", Integer.toString(staleAfterSeconds))
                .withProperty("aidiag.self-monitor.backlog-after-seconds", Integer.toString(backlogAfterSeconds));
        return new AidiagSelfMonitoring(jdbcTemplate, catalogRegistry, meterRegistry, environment, clock);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
