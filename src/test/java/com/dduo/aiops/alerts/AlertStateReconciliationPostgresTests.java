package com.dduo.aiops.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AlertStateReconciliationPostgresTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.5-pg18")
            .withDatabaseName("aidiag").withUsername("aidiag").withPassword("integration-only");

    static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void initializeSchema() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void checkConstraintStillRejectsUnknownObservedStates() {
        var incidentId = insertIncident("corr-check");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO alert_state_reconciliation
                    (incident_id, observed_state, consecutive_observations, last_error_code)
                VALUES (?, 'NOT_A_STATE', 1, 'INCIDENT_RECONCILE_FAILED')
                """, incidentId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void firstFailureWithoutExistingAuditRowIsInserted() {
        var first = insertIncident("corr-first-fail");
        var second = insertIncident("corr-second-ok");
        var tx = mock(TransactionTemplate.class);
        doThrow(new RuntimeException("row failed")).doNothing().when(tx).executeWithoutResult(any());
        var registry = new SimpleMeterRegistry();
        var worker = worker(tx, registry);

        worker.observeIncidents(List.of(identity(first, "local"), identity(second, "production")), Set.of());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT observed_state FROM alert_state_reconciliation WHERE incident_id = ?", String.class, first))
                .isEqualTo("INCIDENT_RECONCILE_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM alert_state_reconciliation WHERE incident_id = ?", String.class, first))
                .isEqualTo("INCIDENT_RECONCILE_FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, first))
                .isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, second))
                .isEqualTo("OPEN");
        assertThat(registry.get("aidiag.reconciliation.runs").tag("outcome", "source_unavailable").counter().count())
                .isZero();
    }

    @Test
    void singleIncidentFailureWritesErrorCodeAndContinues() {
        var first = insertIncident("corr-one");
        var second = insertIncident("corr-two");
        jdbcTemplate.update("""
                INSERT INTO alert_state_reconciliation (incident_id, observed_state, consecutive_observations)
                VALUES (?, 'CONSISTENT', 1)
                """, first);
        var tx = mock(TransactionTemplate.class);
        doThrow(new RuntimeException("row failed")).doNothing().when(tx).executeWithoutResult(any());
        var registry = new SimpleMeterRegistry();
        var worker = worker(tx, registry);

        worker.observeIncidents(List.of(identity(first, "local"), identity(second, "production")), Set.of());

        verify(tx, times(2)).executeWithoutResult(any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT observed_state FROM alert_state_reconciliation WHERE incident_id = ?", String.class, first))
                .isEqualTo("CONSISTENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM alert_state_reconciliation WHERE incident_id = ?", String.class, first))
                .isEqualTo("INCIDENT_RECONCILE_FAILED");
        assertThat(registry.get("aidiag.reconciliation.runs").tag("outcome", "incident_failed").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.get("aidiag.reconciliation.runs").tag("outcome", "source_unavailable").counter().count())
                .isEqualTo(0.0d);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, first))
                .isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, second))
                .isEqualTo("OPEN");
    }

    private static AlertStateReconciliationWorker worker(TransactionTemplate tx, SimpleMeterRegistry registry) {
        return new AlertStateReconciliationWorker(jdbcTemplate, mock(AlertmanagerWebhookService.class),
                new ObjectMapper(), new MockEnvironment().withProperty("DEPLOYMENT_ENVIRONMENT", "local"), registry,
                Clock.fixed(Instant.parse("2026-09-10T06:00:00Z"), ZoneOffset.UTC), tx);
    }

    private static AlertStateReconciliationWorker.IncidentIdentity identity(UUID id, String environment) {
        return new AlertStateReconciliationWorker.IncidentIdentity(id, "AiChatDown", "ai-chat", environment, "OPEN",
                Instant.parse("2026-09-10T05:00:00Z"));
    }

    private static UUID insertIncident(String correlation) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO incident (id, title, primary_service, alert_name, severity, status, started_at, detected_at,
                                      correlation_key, environment, last_alert_at)
                VALUES (?, 'test', 'ai-chat', 'AiChatDown', 'WARNING', 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        ?, 'local', CURRENT_TIMESTAMP)
                """, id, correlation);
        return id;
    }
}
