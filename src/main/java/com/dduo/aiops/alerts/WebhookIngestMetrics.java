package com.dduo.aiops.alerts;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality webhook receive outcomes. Labels are limited to a closed outcome set and never include
 * users, alert ids, URLs, bodies or credentials.
 */
@Component
public class WebhookIngestMetrics {

    private final Counter accepted;
    private final Counter rejected;
    private final Counter failed;
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();

    public WebhookIngestMetrics(MeterRegistry meterRegistry) {
        this.accepted = meterRegistry.counter("aidiag.alert.webhook.ingest", "outcome", "accepted");
        this.rejected = meterRegistry.counter("aidiag.alert.webhook.ingest", "outcome", "rejected");
        this.failed = meterRegistry.counter("aidiag.alert.webhook.ingest", "outcome", "failed");
        Gauge.builder("aidiag.alert.webhook.last.success.unixtime", lastSuccessAt, WebhookIngestMetrics::epochSeconds)
                .register(meterRegistry);
    }

    public void recordAccepted(Instant successAt) {
        accepted.increment();
        lastSuccessAt.set(successAt);
    }

    public void recordRejected() {
        rejected.increment();
    }

    public void recordFailed() {
        failed.increment();
    }

    Instant lastSuccessAt() {
        return lastSuccessAt.get();
    }

    private static double epochSeconds(AtomicReference<Instant> value) {
        var instant = value.get();
        return instant == null ? Double.NaN : instant.getEpochSecond();
    }
}
