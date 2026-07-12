package com.dduo.aiops.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class TelemetrySmokeControllerTests {

    @Test
    void emitsBoundedMetricAndReturnsCorrelationIdentifier() {
        var registry = new SimpleMeterRegistry();
        var clock = Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC);
        var controller = new TelemetrySmokeController(registry, clock);

        var response = controller.emit();

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().smokeRunId()).isNotBlank();
        assertThat(response.getBody().emittedAt()).isEqualTo(Instant.parse("2026-07-11T00:00:00Z"));
        assertThat(registry.find(TelemetrySmokeController.COUNTER_NAME)
                .tag("scenario", "stage0")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
