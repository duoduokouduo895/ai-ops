package com.dduo.faultlab;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AiChatFaultLabTests {

    private static final String TOKEN = "stage5-test-token-with-at-least-32-chars";

    @Test
    void exposesOnlyFixedBoundedMetricsAndResetsRecoveryGauges() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiChatFaultMetrics(registry);

        assertThat(metrics.setActive(AiChatFaultType.MODEL_TIMEOUT, true)).isTrue();
        assertThat(metrics.setActive(AiChatFaultType.MODEL_TIMEOUT, true)).isFalse();
        assertThat(registry.get("aichat.chat.requests").tag("outcome", "timeout").counter().count()).isEqualTo(1D);

        metrics.setActive(AiChatFaultType.R2DBC_POOL_EXHAUSTION, true);
        metrics.setActive(AiChatFaultType.DOCUMENT_BACKLOG, true);
        metrics.setActive(AiChatFaultType.SEARCH_UNAVAILABLE, true);
        assertThat(registry.get("aichat.r2dbc.pool.pending").gauge().value()).isEqualTo(1D);
        assertThat(registry.get("aichat.document.outbox.pending").gauge().value()).isEqualTo(10D);
        assertThat(registry.get("aichat.search.requests").tag("outcome", "error").counter().count()).isEqualTo(3D);

        metrics.setActive(AiChatFaultType.R2DBC_POOL_EXHAUSTION, false);
        metrics.setActive(AiChatFaultType.DOCUMENT_BACKLOG, false);
        assertThat(registry.get("aichat.r2dbc.pool.pending").gauge().value()).isZero();
        assertThat(registry.get("aichat.document.outbox.pending").gauge().value()).isZero();
        assertThat(metrics.snapshot()).containsOnlyKeys("model-timeout", "r2dbc-pool-exhaustion",
                "document-backlog", "search-unavailable");
    }

    @Test
    void hidesControlSurfaceForMissingShortOrWrongTokens() {
        var missing = new AiChatFaultControlController(new SimpleMeterRegistry(), new MockEnvironment());
        assertThat(missing.groundTruth(TOKEN).getStatusCode().value()).isEqualTo(404);

        var shortToken = new AiChatFaultControlController(new SimpleMeterRegistry(),
                new MockEnvironment().withProperty("aichat.stage5.fault.token", "too-short"));
        assertThat(shortToken.groundTruth("too-short").getStatusCode().value()).isEqualTo(404);

        var controller = new AiChatFaultControlController(new SimpleMeterRegistry(),
                new MockEnvironment().withProperty("aichat.stage5.fault.token", TOKEN));
        assertThat(controller.enable("model-timeout", "wrong").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.enable("unknown", TOKEN).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.enable("model-timeout", TOKEN).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.reset(TOKEN).getBody().faults().values()).containsOnly(false);
    }
}
