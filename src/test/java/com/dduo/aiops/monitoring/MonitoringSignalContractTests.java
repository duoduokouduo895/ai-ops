package com.dduo.aiops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Keeps console signal thresholds identical to the alert rules that actually page. */
class MonitoringSignalContractTests {

    @Test
    void everyAlertedAiChatSignalMatchesItsProductionRuleThreshold() throws IOException {
        var rules = Files.readString(Path.of("infra/prometheus/rules/aidiag.yml"));
        var runtimeGroup = rules.substring(rules.indexOf("name: ai-chat-runtime"), rules.indexOf("name: ai-chat-stage5-lab"));

        for (var signal : SignalProfiles.forProfile(SignalProfiles.AI_CHAT)) {
            if (signal.alertName() == null) {
                continue;
            }
            assertThat(rules).as(signal.id()).contains("alert: " + signal.alertName());
            if (signal.threshold() != null) {
                var start = runtimeGroup.indexOf("alert: " + signal.alertName());
                assertThat(start).as(signal.id() + " must be alerted by ai-chat-runtime").isNotNegative();
                var next = runtimeGroup.indexOf("- alert:", start + 1);
                var block = runtimeGroup.substring(start, next < 0 ? runtimeGroup.length() : next);
                var threshold = BigDecimal.valueOf(signal.threshold()).stripTrailingZeros().toPlainString();
                assertThat(block).as(signal.id()).contains(">= " + threshold);
            }
        }
    }

    @Test
    void signalQueriesOnlyInterpolateTheServiceLabel() {
        for (var signal : SignalProfiles.forProfile(SignalProfiles.AI_CHAT)) {
            assertThat(signal.queryTemplate().split("%s", -1)).as(signal.id()).hasSize(2);
            assertThat(signal.query("ai-chat-production")).contains("service_name=\"ai-chat-production\"");
        }
    }
}
