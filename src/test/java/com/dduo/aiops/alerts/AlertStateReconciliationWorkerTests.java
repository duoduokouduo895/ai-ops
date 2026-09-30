package com.dduo.aiops.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class AlertStateReconciliationWorkerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AlertmanagerWebhookService webhookService = mock(AlertmanagerWebhookService.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertStateReconciliationWorker worker = worker(false);

    @Test
    void recoversOneMissingFiringThroughTheExistingIdempotentWebhookPath() throws Exception {
        var alert = objectMapper.readTree("""
                {
                  "labels": {"alertname":"AiChatDown","service_name":"ai-chat","severity":"CRITICAL","environment":"production"},
                  "annotations": {"summary":"Health probe failed"},
                  "startsAt":"2026-07-14T23:59:00Z",
                  "endsAt":"0001-01-01T00:00:00Z",
                  "fingerprint":"reviewed-fingerprint"
                }
                """);

        worker.recoverMissingFirings(List.of(alert), List.of());

        verify(webhookService).accept(any(), any(byte[].class));
    }

    @Test
    void doesNotDuplicateAFiringThatAlreadyHasAnActiveIncidentInTheSameEnvironment() throws Exception {
        var alert = objectMapper.readTree("""
                {"labels":{"alertname":"AiChatDown","service_name":"ai-chat"}}
                """);
        var incident = new AlertStateReconciliationWorker.IncidentIdentity(UUID.randomUUID(), "AiChatDown",
                "ai-chat", "local", "OPEN", Instant.parse("2026-07-14T23:59:00Z"));

        worker.recoverMissingFirings(List.of(alert), List.of(incident));

        verify(webhookService, never()).accept(any(), any(byte[].class));
    }

    @Test
    void sameServiceAndAlertInDifferentEnvironmentsAreNotTheSameIdentity() throws Exception {
        var parsed = objectMapper.readTree("""
                [
                  {"labels":{"alertname":"AiChatDown","service_name":"ai-chat","environment":"production"}},
                  {"labels":{"alertname":"AiChatDown","service_name":"ai-chat","environment":"local"}}
                ]
                """);

        var active = worker.parseActiveAlerts(parsed);
        assertThat(active.identities()).hasSize(2);

        var localIncident = new AlertStateReconciliationWorker.IncidentIdentity(UUID.randomUUID(), "AiChatDown",
                "ai-chat", "local", "OPEN", Instant.parse("2026-07-14T23:59:00Z"));
        worker.recoverMissingFirings(active.documents(), List.of(localIncident));
        verify(webhookService, times(1)).accept(any(), any(byte[].class));
    }

    @Test
    void missingEnvironmentUsesTheSameWebhookDefault() throws Exception {
        var parsed = objectMapper.readTree("""
                [{"labels":{"alertname":"AiChatDown","service_name":"ai-chat"}}]
                """);
        var active = worker.parseActiveAlerts(parsed);
        assertThat(active.identities()).containsExactly(
                new AlertStateReconciliationWorker.AlertIdentity("AiChatDown", "ai-chat", "local"));
    }

    @Test
    void illegalEnvironmentMakesTheSnapshotUnusable() throws Exception {
        var parsed = objectMapper.readTree("""
                [
                  {"labels":{"alertname":"AiChatDown","service_name":"ai-chat","environment":"production"}},
                  {"labels":{"alertname":"AiChatDown","service_name":"ai-chat","environment":"prod env"}}
                ]
                """);
        assertThatThrownBy(() -> worker.parseActiveAlerts(parsed))
                .isInstanceOf(AlertStateReconciliationWorker.SourceQueryException.class)
                .hasMessage("SOURCE_INVALID_ENVIRONMENT");
    }

    @Test
    void oneIncidentFailureDoesNotStopTheRemainingObservationsOrLookLikeSourceFailure() {
        var first = incident("production");
        var second = incident("local");
        doThrow(new RuntimeException("row failed")).doNothing().when(transactionTemplate).executeWithoutResult(any());
        when(jdbcTemplate.update(contains("INCIDENT_RECONCILE_FAILED"), any(UUID.class))).thenReturn(1);

        worker.observeIncidents(List.of(first, second), java.util.Set.of());

        verify(transactionTemplate, times(2)).executeWithoutResult(any());
        verify(jdbcTemplate).update(contains("ON CONFLICT"), eq(first.id()));
        verify(jdbcTemplate, never()).update(contains("SOURCE_UNAVAILABLE"), any(Object.class));
        assertThat(meterRegistry.get("aidiag.reconciliation.runs").tag("outcome", "incident_failed").counter().count())
                .isEqualTo(1.0d);
        assertThat(meterRegistry.get("aidiag.reconciliation.runs").tag("outcome", "source_unavailable").counter().count())
                .isEqualTo(0.0d);
    }

    @Test
    void observeModeDoesNotUpdateIncidentOrRunStateWhenAnAlertIsAbsent() {
        when(jdbcTemplate.queryForObject(contains("alert_state_reconciliation"), eq(Integer.class), any(), any(), any()))
                .thenReturn(3);
        worker.reconcileIncident(incident("local"), java.util.Set.of());
        verify(jdbcTemplate, never()).update(contains("SET status = 'RESOLVED'"), any(Object.class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO diagnosis_run"), any(), any(), any(), any());
    }

    @Test
    void sourceFailureCodeDoesNotLeakExceptionDetails() {
        when(jdbcTemplate.update(contains("SOURCE_UNAVAILABLE"), eq("SOURCE_UNAVAILABLE"))).thenReturn(1);
        worker.reconcile();
        verify(jdbcTemplate).update(contains("SOURCE_UNAVAILABLE"), eq("SOURCE_UNAVAILABLE"));
        verify(jdbcTemplate, never()).update(contains("SET status = 'RESOLVED'"), any(Object.class));
        verify(webhookService, never()).accept(any(), any());
        assertThat(meterRegistry.get("aidiag.reconciliation.runs").tag("outcome", "source_unavailable").counter().count())
                .isEqualTo(1.0d);
    }

    private AlertStateReconciliationWorker.IncidentIdentity incident(String environment) {
        return new AlertStateReconciliationWorker.IncidentIdentity(UUID.randomUUID(), "AiChatDown", "ai-chat",
                environment, "OPEN", Instant.parse("2026-07-14T23:59:00Z"));
    }

    private AlertStateReconciliationWorker worker(boolean applyTransitions) {
        var environment = new MockEnvironment()
                .withProperty("aidiag.reconciliation.apply-transitions", Boolean.toString(applyTransitions))
                .withProperty("DEPLOYMENT_ENVIRONMENT", "local");
        return new AlertStateReconciliationWorker(jdbcTemplate, webhookService, objectMapper, environment, meterRegistry,
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC), transactionTemplate);
    }
}
