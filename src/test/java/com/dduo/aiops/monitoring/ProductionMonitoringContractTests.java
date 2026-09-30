package com.dduo.aiops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProductionMonitoringContractTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void productionTargetsAreDynamicWhileCandidateTargetsUseVerifiedAliases() throws IOException {
        var prometheus = read("infra/prometheus/prometheus.yml");
        var compose = read("compose.yaml");

        assertThat(prometheus)
                .contains("job_name: ai-chat-candidate", "targets: [ai-chat-candidate:9091]")
                .contains("elasticsearch-v9-candidate:9200", "nacos-v3-candidate:8848")
                .contains("job_name: ai-chat-production", "/etc/prometheus/targets/ai-chat-production.json")
                .contains("regex: metrics", "regex: probe|");
        assertThat(prometheus)
                .doesNotContain("targets: [elasticsearch:9200]", "targets: [nacos:8848]")
                .doesNotContain("targets: [ai-chat-production:9091]");
        assertThat(compose).contains("AIDIAG_PROMETHEUS_TARGETS_DIR", ":/etc/prometheus/targets:ro");
    }

    @Test
    void rulesAndLogsPreserveCandidateProductionIdentity() throws IOException {
        var alerts = read("infra/prometheus/rules/aidiag.yml");
        var alloy = read("infra/alloy/config.alloy");

        assertThat(alerts)
                .contains("name: ai-chat-runtime", "ai-chat-(candidate|production)")
                .contains("probe_success{job=\"blackbox-ai-chat\",target_kind=\"application\",service_name!=\"ai-chat-candidate\"}")
                .contains("probe_success{job=\"blackbox-ai-chat\",target_kind=\"dependency\"}");
        assertThat(alloy)
                .contains("/var/log/ai-chat/operations.jsonl", "service_name\" = \"ai-chat-candidate")
                .contains("/var/log/ai-chat/production-operations.jsonl", "service_name\" = \"ai-chat-production");
    }

    @Test
    void productionTargetPresenceSuppressesOnlyTheIntentionallyStoppedCandidateProcessAlert() throws IOException {
        var alerts = read("infra/prometheus/rules/aidiag.yml");

        assertThat(alerts)
                .contains("service_name!=\"ai-chat-candidate\"")
                .contains("service_name=\"ai-chat-candidate\"")
                .contains("target_kind=\"ingress\",service_name=\"ai-chat-production\"")
                .contains("unless on()")
                .contains("count(up{job=\"ai-chat-production\"})");
        assertThat(alerts)
                .contains("probe_success{job=\"blackbox-ai-chat\",target_kind=\"dependency\"} == 0");
    }

    @Test
    void dashboardsRequireAnExplicitCandidateOrProductionSelection() throws IOException {
        assertDashboardUsesVariable("infra/grafana/dashboards/ai-chat-operations.json");
        assertDashboardUsesVariable("infra/grafana/dashboards/ai-chat-break-glass.json");
    }

    private void assertDashboardUsesVariable(String path) throws IOException {
        JsonNode dashboard = objectMapper.readTree(Path.of(path).toFile());
        var variable = dashboard.path("templating").path("list").findValue("name");
        assertThat(variable).isNotNull();
        assertThat(variable.asText()).isEqualTo("service_name");
        assertThat(dashboard.path("templating").toString())
                .contains("ai-chat-candidate", "ai-chat-production");

        for (var panel : dashboard.path("panels")) {
            for (var target : panel.path("targets")) {
                var expression = target.path("expr").asText();
                if (!expression.isBlank()) {
                    assertThat(expression).as("panel %s must filter the selected service", panel.path("title").asText())
                            .contains("service_name");
                }
            }
        }
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
