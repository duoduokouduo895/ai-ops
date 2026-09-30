package com.dduo.aiops.console.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ConsoleIncidentQueryServiceTests {

    private static final Instant GENERATED_AT = Instant.parse("2026-07-15T05:47:57Z");

    @Test
    void metricSeriesExposesOnlyNumericCurveFields() {
        var service = new ConsoleIncidentQueryService(null, new com.fasterxml.jackson.databind.ObjectMapper());

        var series = service.metricSeries("""
                {"sampleCount":3,"maximum":4.0,"threshold":1,"query":"secret",
                 "series":[[100,1],[130,4],["x",2],[160,"NaN"]]}
                """);

        assertThat(series.threshold()).isEqualTo(1.0);
        assertThat(series.maximum()).isEqualTo(4.0);
        assertThat(series.points()).hasSize(2);
        assertThat(series.points().get(1)).containsExactly(130, 4);
        assertThat(service.metricSeries(null)).isNull();
        assertThat(service.metricSeries("not json")).isNull();
    }

    @Test
    void supportedPrimaryIsTheOnlyRootCause() {
        var runId = UUID.randomUUID();
        var primary = candidate(runId, 1, "SUPPORTED", "pool exhaustion");
        var view = ReportProjection.project(report(runId, primary.id()), List.of(primary));

        assertThat(view.rootCause()).isEqualTo("pool exhaustion");
        assertThat(view.verificationStatus()).isEqualTo("SUPPORTED");
        assertThat(view.disposition()).isEqualTo("CONFIRMED_ROOT_CAUSE");
        assertThat(view.candidates()).hasSize(1);
        assertThat(view.diagnosisRunId()).isEqualTo(runId);
    }

    @Test
    void weaklySupportedCandidateIsNotPresentedAsRootCause() {
        var runId = UUID.randomUUID();
        var candidate = candidate(runId, 1, "WEAKLY_SUPPORTED", "possible timeout");
        var view = ReportProjection.project(report(runId, null), List.of(candidate));

        assertThat(view.rootCause()).isNull();
        assertThat(view.verificationStatus()).isEqualTo("WEAKLY_SUPPORTED");
        assertThat(view.disposition()).isEqualTo("WEAKLY_SUPPORTED");
        assertThat(view.description()).contains("possible timeout");
        assertThat(view.dispositionReason()).contains("not a confirmed root cause");
        assertThat(view.candidates()).extracting(ConsoleIncidentQueryService.ReportCandidateView::name)
                .containsExactly("possible timeout");
    }

    @Test
    void insufficientEvidenceStillReturnsAnExplainableReport() {
        var runId = UUID.randomUUID();
        var candidate = candidate(runId, 1, "INSUFFICIENT_EVIDENCE", "unconfirmed rule");
        var view = ReportProjection.project(report(runId, null, List.of("Metric 与 Log 两类独立 P0 证据未同时满足")),
                List.of(candidate));

        assertThat(view.rootCause()).isNull();
        assertThat(view.disposition()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(view.limitations()).contains("Metric 与 Log 两类独立 P0 证据未同时满足");
        assertThat(view.verificationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void missingCandidatesKeepTheReportReadable() {
        var runId = UUID.randomUUID();
        var view = ReportProjection.project(report(runId, UUID.randomUUID()), List.of());

        assertThat(view.rootCause()).isNull();
        assertThat(view.disposition()).isEqualTo("NO_CANDIDATE");
        assertThat(view.description()).contains("没有可确认的根因");
        assertThat(view.candidates()).isEmpty();
    }

    @Test
    void candidatesFromAnotherRunAreDroppedAndCannotBecomeRootCause() {
        var reportRun = UUID.randomUUID();
        var otherRun = UUID.randomUUID();
        var foreign = candidate(otherRun, 1, "SUPPORTED", "foreign root cause");
        var view = ReportProjection.project(report(reportRun, foreign.id()), List.of(foreign));

        assertThat(view.rootCause()).isNull();
        assertThat(view.disposition()).isEqualTo("NO_CANDIDATE");
        assertThat(view.candidates()).isEmpty();
        assertThat(view.dispositionReason()).contains("no in-run candidate");
    }

    @Test
    void supportedCandidateThatIsNotPrimaryCannotFillRootCause() {
        var runId = UUID.randomUUID();
        var candidate = candidate(runId, 1, "SUPPORTED", "not linked as primary");
        var view = ReportProjection.project(report(runId, null), List.of(candidate));

        assertThat(view.rootCause()).isNull();
        assertThat(view.disposition()).isEqualTo("INCOMPLETE");
        assertThat(view.verificationStatus()).isEqualTo("SUPPORTED");
    }

    @Test
    void projectionStripsControlsAndDoesNotCarryRawJsonFields() {
        var runId = UUID.randomUUID();
        var candidate = new ReportProjection.CandidateRecord(UUID.randomUUID(), runId, 1,
                "name\u0007with-control", "SELECT secret FROM logs", "ai-chat", 12.0d, "INSUFFICIENT_EVIDENCE", 0, 0);
        var view = ReportProjection.project(report(runId, null, List.of("ok limitation", "x".repeat(600))),
                List.of(candidate));

        assertThat(view.rootCause()).isNull();
        assertThat(view.candidates().getFirst().name()).isEqualTo("name with-control");
        assertThat(view.limitations()).allMatch(item -> item.length() <= 500);
        assertThat(view.toString()).doesNotContain("report_json", "prompt", "raw_data", "query_spec");
    }

    private static ReportProjection.ReportRecord report(UUID runId, UUID primaryId) {
        return report(runId, primaryId, List.of());
    }

    private static ReportProjection.ReportRecord report(UUID runId, UUID primaryId, List<String> limitations) {
        return new ReportProjection.ReportRecord(UUID.randomUUID(), GENERATED_AT, runId, primaryId, limitations);
    }

    private static ReportProjection.CandidateRecord candidate(UUID runId, int rank, String status, String name) {
        return new ReportProjection.CandidateRecord(UUID.randomUUID(), runId, rank, name, "description for " + name,
                "ai-chat", 40.0d, status, 1, 0);
    }
}
