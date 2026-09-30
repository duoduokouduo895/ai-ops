package com.dduo.aiops.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.dduo.aiops.alerts.AlertmanagerWebhookService;
import com.dduo.aiops.advisory.AiAdvisoryRepository;
import com.dduo.aiops.diagnosis.DiagnosisReportController;
import com.dduo.aiops.diagnosis.DiagnosisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Runs only from the Compose test profile against the real PostgreSQL service. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AIDIAG_INTEGRATION_TESTS", matches = "true")
@SpringBootTest(properties = { "aidiag.worker.enabled=false", "aidiag.smoke.enabled=false", "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false", "aidiag.stage3.enabled=false" })
class DiagnosisPipelinePostgresIntegrationTests {

    @Autowired
    private AlertmanagerWebhookService webhookService;

    @Autowired
    private DiagnosisRunRepository diagnosisRunRepository;

    @Autowired
    private DiagnosisRepository stage3DiagnosisRepository;

    @Autowired
    private AiAdvisoryRepository stage4Repository;

    @Autowired
    private DiagnosisReportController diagnosisReportController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<TestScope> scopes = new ArrayList<>();

    @Test
    @Transactional
    void stageFourDailyBudgetIsAtomicAndBounded() {
        jdbcTemplate.update("DELETE FROM ai_model_daily_budget WHERE usage_date = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date");

        assertThat(stage4Repository.reserveDailyRequest(2)).isTrue();
        assertThat(stage4Repository.reserveDailyRequest(2)).isTrue();
        assertThat(stage4Repository.reserveDailyRequest(2)).isFalse();
    }

    @AfterEach
    void cleanUp() {
        for (var scope : scopes) {
            jdbcTemplate.update("DELETE FROM incident WHERE correlation_key = ?", scope.correlationKey());
            jdbcTemplate.update("DELETE FROM alert_event WHERE receiver = ? AND group_key = ?", scope.receiver(), scope.groupKey());
        }
        scopes.clear();
    }

    @Test
    void concurrentDuplicateWebhooksCreateOneEventIncidentAndRun() throws Exception {
        var scope = scope("payment-service");
        var executor = Executors.newFixedThreadPool(12);
        var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<AlertmanagerWebhookService.WebhookReceipt>>();
            for (var index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> {
                    gate.await();
                    return webhookService.accept(scope.payload(), scope.rawBody());
                }));
            }
            gate.countDown();

            var accepted = 0;
            var duplicates = 0;
            for (var future : futures) {
                var receipt = future.get();
                accepted += receipt.acceptedEvents();
                duplicates += receipt.duplicateEvents();
            }

            assertThat(accepted).isEqualTo(1);
            assertThat(duplicates).isEqualTo(11);
            assertThat(count("SELECT count(*) FROM alert_event WHERE receiver = ? AND group_key = ?", scope)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM incident WHERE correlation_key = ?", scope.correlationKey())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM diagnosis_run r JOIN incident i ON i.id = r.incident_id WHERE i.correlation_key = ?",
                    scope.correlationKey())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void delayedResolvedGroupCannotCloseANewerFiringIncident() throws Exception {
        var scope = scope("payment-service");
        var firingAlert = (ObjectNode) scope.payload().path("alerts").get(0);
        firingAlert.put("startsAt", "2026-07-12T00:10:00Z");
        webhookService.accept(scope.payload(), rawBody(scope.payload()));

        scope.payload().put("status", "resolved");
        firingAlert.put("status", "resolved");
        firingAlert.put("startsAt", "2026-07-12T00:00:00Z");
        firingAlert.put("endsAt", "2026-07-12T00:05:00Z");
        webhookService.accept(scope.payload(), rawBody(scope.payload()));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM incident WHERE correlation_key = ?", String.class,
                scope.correlationKey())).isEqualTo("OPEN");
    }

    @Test
    void resolvedGroupClosesEveryEnvironmentCorrelationIndependently() {
        var scope = scope("payment-service");
        var secondAlert = scope.payload().withArray("alerts").addObject();
        secondAlert.put("status", "firing");
        secondAlert.put("fingerprint", "stage2-it-second-" + UUID.randomUUID());
        secondAlert.put("startsAt", "2026-07-12T00:00:00Z");
        var secondLabels = secondAlert.putObject("labels");
        secondLabels.put("alertname", "Stage2IntegrationTest");
        secondLabels.put("service_name", "payment-service");
        secondLabels.put("severity", "warning");
        secondLabels.put("environment", "integration-test-secondary");
        secondAlert.putObject("annotations").put("summary", "integration test only");
        var secondCorrelationKey = correlationKey("integration-test-secondary", scope.receiver(), scope.groupKey());
        scopes.add(new TestScope(scope.receiver(), scope.groupKey(), secondCorrelationKey, scope.payload(), scope.rawBody()));

        webhookService.accept(scope.payload(), rawBody(scope.payload()));
        scope.payload().put("status", "resolved");
        for (var alertNode : scope.payload().withArray("alerts")) {
            var alert = (ObjectNode) alertNode;
            alert.put("status", "resolved");
            alert.put("endsAt", "2026-07-12T00:05:00Z");
        }

        webhookService.accept(scope.payload(), rawBody(scope.payload()));

        assertThat(jdbcTemplate.queryForList("""
                SELECT status FROM incident WHERE correlation_key IN (?, ?) ORDER BY correlation_key
                """, String.class, scope.correlationKey(), secondCorrelationKey)).containsExactly("RESOLVED", "RESOLVED");
    }

    @Test
    void expiredWorkerCannotWriteStageTwoHandoffAudit() {
        var scope = scope("payment-service");
        var run = directRun(scope, "COLLECTING");
        var staleToken = UUID.randomUUID();

        jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET status = 'COLLECTING', lease_owner = ?, lease_token = ?, lease_generation = 1,
                    lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, "stale-worker", staleToken, run.id());

        var handedOff = diagnosisRunRepository.recordStageTwoHandoff(
                new DiagnosisRunRepository.ClaimedRun(run.id(), run.incidentId(), 1, "stale-worker", staleToken));

        assertThat(handedOff).isFalse();
        assertThat(count("SELECT count(*) FROM tool_call_audit WHERE diagnosis_run_id = ? AND tool_name = 'stage2-ingestion-handoff'",
                run.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM diagnosis_run WHERE id = ?", String.class, run.id()))
                .isEqualTo("COLLECTING");
    }

    @Test
    void expiredCollectingRunIsReclaimedWithNewLeaseGeneration() {
        var scope = scope("payment-service");
        var run = directRun(scope, "COLLECTING");
        var oldToken = UUID.randomUUID();

        jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET status = 'COLLECTING', lease_owner = ?, lease_token = ?, lease_generation = 1,
                    lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, "dead-worker", oldToken, run.id());

        var reclaimed = diagnosisRunRepository.claimNext("recovery-worker", 30).orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(run.id());
        assertThat(reclaimed.leaseToken()).isNotEqualTo(oldToken);
        assertThat(jdbcTemplate.queryForObject("SELECT lease_generation FROM diagnosis_run WHERE id = ?", Long.class, run.id()))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM diagnosis_run WHERE id = ?", String.class, run.id()))
                .isEqualTo("LEASED");
    }

    @Test
    void oversizedDatabaseBoundedLabelIsRejectedBeforePersistence() {
        var scope = scope("x".repeat(129));

        assertThatThrownBy(() -> webhookService.accept(scope.payload(), scope.rawBody()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service_name");
        assertThat(count("SELECT count(*) FROM alert_event WHERE receiver = ? AND group_key = ?", scope)).isZero();
    }

    @Test
    void stageThreePersistsOnlyWhileItsLeaseIsCurrent() throws Exception {
        var scope = scope("payment-service");
        var run = directRun(scope, "ANALYZING");
        var token = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET status = 'ANALYZING', lease_owner = ?, lease_token = ?, lease_generation = 1,
                    lease_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                WHERE id = ?
                """, "stage3-test", token, run.id());
        var claimed = new DiagnosisRepository.ClaimedAnalysisRun(run.id(), run.incidentId(), 1, "stage3-test", token);
        var incident = stage3DiagnosisRepository.incident(run.incidentId());
        var evidence = new DiagnosisRepository.EvidenceDraft("METRIC", "test", incident.startedAt(), incident.lastAlertAt(),
                "{\"template\":\"test\"}", "test evidence", "{\"matched\":true}", BigDecimal.valueOf(30), true);
        var result = new DiagnosisRepository.DiagnosisResult("TEST_RULE", "TEST_GROUND_TRUTH", "test-only deterministic result", BigDecimal.valueOf(65),
                "SUPPORTED", "[]", List.of(new DiagnosisRepository.ToolObservation("queryServiceMetrics", "{\"template\":\"test\"}",
                "{\"matched\":true}", "SUCCEEDED", 1, null, evidence)), List.of("integration test"));

        var receipt = stage3DiagnosisRepository.finalizeReport(claimed, incident, result);

        assertThat(receipt).isPresent();
        assertThat(count("SELECT count(*) FROM diagnosis_report WHERE diagnosis_run_id = ?", run.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM evidence WHERE diagnosis_run_id = ?", run.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM diagnosis_run WHERE id = ?", String.class, run.id()))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, run.incidentId()))
                .isEqualTo("DIAGNOSED");
        var response = objectMapper.readTree(diagnosisReportController.latest(run.incidentId()).getBody());
        assertThat(response.path("verificationStatus").asText()).isEqualTo("SUPPORTED");
        assertThat(response.path("primaryRootCauseCandidateId").asText()).isNotBlank();
        var candidateId = receipt.orElseThrow().candidateId();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE root_cause_candidate SET verification_status = 'REJECTED' WHERE id = ?", candidateId))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT verification_status FROM root_cause_candidate WHERE id = ?",
                String.class, candidateId)).isEqualTo("SUPPORTED");
    }

    @Test
    void stageThreeDoesNotPromoteInsufficientEvidenceToPrimaryRootCause() {
        var scope = scope("payment-service");
        var run = directRun(scope, "ANALYZING");
        var token = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET status = 'ANALYZING', lease_owner = ?, lease_token = ?, lease_generation = 1,
                    lease_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                WHERE id = ?
                """, "stage3-insufficient-test", token, run.id());
        var claimed = new DiagnosisRepository.ClaimedAnalysisRun(run.id(), run.incidentId(), 1,
                "stage3-insufficient-test", token);
        var incident = stage3DiagnosisRepository.incident(run.incidentId());
        var result = new DiagnosisRepository.DiagnosisResult("NO_DETERMINISTIC_RULE", "UNSPECIFIED", "missing independent evidence",
                BigDecimal.ZERO, "INSUFFICIENT_EVIDENCE", "[]", List.of(), List.of("integration test"));

        assertThat(stage3DiagnosisRepository.finalizeReport(claimed, incident, result)).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT primary_root_cause_id IS NULL FROM diagnosis_report WHERE diagnosis_run_id = ?",
                Boolean.class, run.id())).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, run.incidentId()))
                .isEqualTo("OPEN");
    }

    @Test
    void databaseRejectsAnUnsupportedCandidateAsThePrimaryRootCause() {
        var scope = scope("payment-service");
        var run = directRun(scope, "COMPLETED");
        var candidateId = jdbcTemplate.queryForObject("""
                INSERT INTO root_cause_candidate (diagnosis_run_id, candidate_rank, name, description, affected_service,
                                                  support_score, verification_status, remediation)
                VALUES (?, 1, 'UNVERIFIED_TEST', 'must never be primary', 'payment-service', 1,
                        'INSUFFICIENT_EVIDENCE', '[]'::jsonb)
                RETURNING id
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), run.id());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO diagnosis_report (incident_id, diagnosis_run_id, report_version, primary_root_cause_id,
                                              report_json, report_markdown)
                VALUES (?, ?, 1, ?, '{}'::jsonb, 'test-only report')
                """, run.incidentId(), run.id(), candidateId)).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM diagnosis_report WHERE diagnosis_run_id = ?", run.id())).isZero();
    }

    @Test
    void expiredStageThreeLeaseCannotWriteReportOrEvidence() {
        var scope = scope("payment-service");
        var run = directRun(scope, "ANALYZING");
        var token = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET status = 'ANALYZING', lease_owner = ?, lease_token = ?, lease_generation = 1,
                    lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, "expired-stage3-test", token, run.id());
        var claimed = new DiagnosisRepository.ClaimedAnalysisRun(run.id(), run.incidentId(), 1, "expired-stage3-test", token);
        var incident = stage3DiagnosisRepository.incident(run.incidentId());
        var result = new DiagnosisRepository.DiagnosisResult("TEST_RULE", "TEST_GROUND_TRUTH", "must not persist", BigDecimal.ZERO,
                "INSUFFICIENT_EVIDENCE", "[]", List.of(), List.of("expired lease"));

        assertThat(stage3DiagnosisRepository.finalizeReport(claimed, incident, result)).isEmpty();
        assertThat(count("SELECT count(*) FROM diagnosis_report WHERE diagnosis_run_id = ?", run.id())).isZero();
        assertThat(count("SELECT count(*) FROM evidence WHERE diagnosis_run_id = ?", run.id())).isZero();
    }

    private TestScope scope(String serviceName) {
        var suffix = UUID.randomUUID().toString();
        var receiver = "stage2-it-" + suffix;
        var groupKey = "stage2-it-group-" + suffix;
        var payload = objectMapper.createObjectNode();
        payload.put("receiver", receiver);
        payload.put("groupKey", groupKey);
        var alert = payload.putArray("alerts").addObject();
        alert.put("status", "firing");
        alert.put("fingerprint", "stage2-it-" + suffix);
        alert.put("startsAt", Instant.parse("2026-07-12T00:00:00Z").toString());
        var labels = alert.putObject("labels");
        labels.put("alertname", "Stage2IntegrationTest");
        labels.put("service_name", serviceName);
        labels.put("severity", "warning");
        labels.put("environment", "integration-test");
        alert.putObject("annotations").put("summary", "integration test only");
        var scope = new TestScope(receiver, groupKey, correlationKey("integration-test", receiver, groupKey), payload, rawBody(payload));
        scopes.add(scope);
        return scope;
    }

    private Run run(TestScope scope) {
        return jdbcTemplate.queryForObject("""
                SELECT r.id, r.incident_id
                FROM diagnosis_run r
                JOIN incident i ON i.id = r.incident_id
                WHERE i.correlation_key = ?
                """, (resultSet, rowNumber) -> new Run(UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("incident_id"))), scope.correlationKey());
    }

    private Run directRun(TestScope scope, String status) {
        var incidentId = jdbcTemplate.queryForObject("""
                INSERT INTO incident (title, primary_service, alert_name, severity, status, started_at, detected_at,
                                      correlation_key, environment, last_alert_at)
                VALUES ('Stage2 integration test', 'payment-service', 'Stage2IntegrationTest', 'WARNING', 'OPEN',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'integration-test', CURRENT_TIMESTAMP)
                RETURNING id
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")), scope.correlationKey());
        var runId = jdbcTemplate.queryForObject("""
                INSERT INTO diagnosis_run (incident_id, attempt, run_kind, status, available_at)
                VALUES (?, 1, 'INITIAL', ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                RETURNING id
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")), incidentId, status);
        return new Run(runId, incidentId);
    }

    private int count(String sql, TestScope scope) {
        return jdbcTemplate.queryForObject(sql, Integer.class, scope.receiver(), scope.groupKey());
    }

    private int count(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Integer.class, argument);
    }

    private byte[] rawBody(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize integration test payload", exception);
        }
    }

    private static String correlationKey(String environment, String receiver, String groupKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((environment + "\n" + receiver + "\n" + groupKey).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record TestScope(String receiver, String groupKey, String correlationKey, ObjectNode payload, byte[] rawBody) {
    }

    private record Run(UUID id, UUID incidentId) {
    }
}
