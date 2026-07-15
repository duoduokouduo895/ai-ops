package com.dduo.aiops.catalog;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps a small, failure-tolerant self-observability surface independent from the console UI. */
@Component
public class AidiagSelfMonitoring {

    private final JdbcTemplate jdbcTemplate;
    private final ServiceCatalogRegistry catalogRegistry;
    private final AtomicInteger catalogServices = new AtomicInteger();
    private final AtomicLong activeIncidents = new AtomicLong();
    private final AtomicLong pendingDiagnosisRuns = new AtomicLong();
    private final AtomicInteger databaseReachable = new AtomicInteger();

    public AidiagSelfMonitoring(JdbcTemplate jdbcTemplate, ServiceCatalogRegistry catalogRegistry,
            MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogRegistry = catalogRegistry;
        Gauge.builder("aidiag.catalog.services", catalogServices, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("aidiag.incidents.active", activeIncidents, AtomicLong::get).register(meterRegistry);
        Gauge.builder("aidiag.diagnosis.pending", pendingDiagnosisRuns, AtomicLong::get).register(meterRegistry);
        Gauge.builder("aidiag.database.reachable", databaseReachable, AtomicInteger::get).register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${aidiag.self-monitor.initial-delay-ms:15000}",
            fixedDelayString = "${aidiag.self-monitor.delay-ms:15000}")
    public void refresh() {
        catalogServices.set(catalogRegistry.snapshot().services().size());
        try {
            activeIncidents.set(value("SELECT count(*) FROM incident WHERE status IN ('OPEN','DIAGNOSING','DIAGNOSED','FAILED')"));
            pendingDiagnosisRuns.set(value("SELECT count(*) FROM diagnosis_run WHERE status IN ('PENDING','LEASED','COLLECTING','EVIDENCE_READY','ANALYZING','VERIFYING','GENERATING_REPORT','RETRY_WAIT')"));
            databaseReachable.set(1);
        } catch (RuntimeException exception) {
            databaseReachable.set(0);
        }
    }

    private long value(String sql) {
        var result = jdbcTemplate.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
