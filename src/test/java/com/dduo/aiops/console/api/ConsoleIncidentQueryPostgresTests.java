package com.dduo.aiops.console.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ConsoleIncidentQueryPostgresTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.5-pg18")
            .withDatabaseName("aidiag").withUsername("aidiag").withPassword("integration-only");

    static ConsoleIncidentQueryService queryService;
    static NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeAll
    static void initializeSchema() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        queryService = new ConsoleIncidentQueryService(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void missingReportIsNotFound() {
        assertThatThrownBy(() -> queryService.report(UUID.fromString("00000000-0000-4000-8000-000000000099")))
                .isInstanceOf(ConsoleResourceNotFoundException.class);
    }

    @Test
    void mapsAnInsufficientReportWithoutPromotingTheCandidateToRootCause() {
        var incidentId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        var runId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        var candidateId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        jdbcTemplate.getJdbcTemplate().update("""
                INSERT INTO incident (id, title, primary_service, alert_name, severity, status, started_at, detected_at,
                                      correlation_key, environment, last_alert_at)
                VALUES (?, 'test', 'ai-chat', 'AiChatDown', 'WARNING', 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        'corr-report-sql', 'local', CURRENT_TIMESTAMP)
                """, incidentId);
        jdbcTemplate.getJdbcTemplate().update("""
                INSERT INTO diagnosis_run (id, incident_id, attempt, run_kind, status)
                VALUES (?, ?, 1, 'INITIAL', 'COMPLETED')
                """, runId, incidentId);
        jdbcTemplate.getJdbcTemplate().update("""
                INSERT INTO root_cause_candidate (id, diagnosis_run_id, candidate_rank, name, description, affected_service,
                                                  support_score, verification_status, remediation)
                VALUES (?, ?, 1, 'unconfirmed timeout', 'weak candidate', 'ai-chat', 25, 'INSUFFICIENT_EVIDENCE', '[]'::jsonb)
                """, candidateId, runId);
        jdbcTemplate.getJdbcTemplate().update("""
                INSERT INTO diagnosis_report (incident_id, diagnosis_run_id, report_version, primary_root_cause_id,
                                              report_json, report_markdown)
                VALUES (?, ?, 1, NULL, '{"limitations":["Metric 与 Log 未同时满足"]}'::jsonb, 'redacted')
                """, incidentId, runId);

        var view = queryService.report(incidentId);
        assertThat(view.rootCause()).isNull();
        assertThat(view.disposition()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(view.candidates()).extracting(ConsoleIncidentQueryService.ReportCandidateView::name)
                .containsExactly("unconfirmed timeout");
        assertThat(view.limitations()).contains("Metric 与 Log 未同时满足");
        assertThat(view.toString()).doesNotContain("report_markdown", "prompt");
    }
}
