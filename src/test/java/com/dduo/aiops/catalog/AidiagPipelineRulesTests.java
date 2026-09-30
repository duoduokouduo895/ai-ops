package com.dduo.aiops.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AidiagPipelineRulesTests {

    @Test
    void pipelineRulesDistinguishIdleInProgressAndFailureWithoutStringGuessing() throws Exception {
        var rulesText = Files.readString(Path.of("infra/prometheus/rules/aidiag-pipeline.yml"));
        var prometheus = Files.readString(Path.of("infra/prometheus/prometheus.yml"));
        assertThat(prometheus).contains("/etc/prometheus/rules/*.yml");

        Map<String, Object> document = new Yaml().load(rulesText);
        var alerts = alertsByName(document);
        assertThat(expr(alerts, "AIDiagAlertmanagerWebhookFailed"))
                .isEqualTo("increase(alertmanager_notifications_failed_total{integration=\"webhook\"}[15m]) > 0");
        assertThat(expr(alerts, "AIDiagWebhookIngestRejected"))
                .isEqualTo("increase(aidiag_alert_webhook_ingest_total{outcome=\"rejected\"}[15m]) > 0");
        assertThat(expr(alerts, "AIDiagWebhookIngestFailed"))
                .isEqualTo("increase(aidiag_alert_webhook_ingest_total{outcome=\"failed\"}[15m]) > 0");
        assertThat(expr(alerts, "AIDiagPipelineBackendUnreachable")).isEqualTo("up{job=\"aidiag\"} == 0");
        assertThat(expr(alerts, "AIDiagPipelineSelfCheckFailed")).isEqualTo("aidiag_database_reachable == 0");
        assertThat(expr(alerts, "AIDiagPipelineSelfCheckStale"))
                .isEqualTo("aidiag_pipeline_last_success_unixtime > 0 and (time() - aidiag_pipeline_last_success_unixtime) > 120");
        assertThat(expr(alerts, "AIDiagDiagnosisBacklog")).isEqualTo("aidiag_diagnosis_backlog == 1");
        assertThat(alerts.keySet()).doesNotContain("AIDiagDiagnosisPending");
        for (var alert : alerts.values()) {
            var expr = String.valueOf(alert.get("expr"));
            assertThat(expr).doesNotContain("absent(");
            assertThat(expr).doesNotContain("aidiag_diagnosis_pending > 0");
            assertThat(expr).doesNotContain("last_alert");
        }
    }

    @Test
    void continuousDatabaseDownFiresSelfCheckFailedUnderPromtool() throws Exception {
        var home = Path.of("target", "it", "prometheus-3.13.1");
        var configured = System.getenv("PROMETHEUS_HOME");
        if (configured != null && !configured.isBlank()) {
            home = Path.of(configured);
        }
        var promtool = home.resolve(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "promtool.exe" : "promtool");
        assumeThat(Files.isRegularFile(promtool)).as("promtool v3.13.1").isTrue();
        var process = new ProcessBuilder(promtool.toAbsolutePath().toString(), "test", "rules",
                "infra/prometheus/tests/aidiag-pipeline-selfcheck.test.yml")
                .directory(Path.of(".").toAbsolutePath().normalize().toFile());
        process.redirectErrorStream(true);
        var started = process.start();
        var output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(started.waitFor()).as(output).isZero();
        assertThat(output).contains("SUCCESS").doesNotContain("FAILED");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> alertsByName(Map<String, Object> document) {
        var alerts = new LinkedHashMap<String, Map<String, Object>>();
        var groups = (List<Map<String, Object>>) document.get("groups");
        assertThat(groups).isNotEmpty();
        for (var group : groups) {
            var rules = (List<Map<String, Object>>) group.get("rules");
            for (var rule : rules) {
                alerts.put(String.valueOf(rule.get("alert")), rule);
            }
        }
        return alerts;
    }

    private static String expr(Map<String, Map<String, Object>> alerts, String name) {
        assertThat(alerts).containsKey(name);
        return String.valueOf(alerts.get(name).get("expr")).trim();
    }
}
