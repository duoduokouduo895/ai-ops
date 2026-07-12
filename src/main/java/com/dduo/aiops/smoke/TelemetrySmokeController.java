package com.dduo.aiops.smoke;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emits a bounded, side-effect-free Stage 0 telemetry signal. The controller is absent unless
 * explicitly enabled for a smoke run.
 */
@RestController
@RequestMapping("/internal/smoke")
@ConditionalOnProperty(prefix = "aidiag.smoke", name = "enabled", havingValue = "true")
public class TelemetrySmokeController {

    static final String COUNTER_NAME = "aidiag.smoke.telemetry.events";

    private static final Logger log = LoggerFactory.getLogger(TelemetrySmokeController.class);

    private final Clock clock;
    private final Counter emittedCounter;

    public TelemetrySmokeController(MeterRegistry meterRegistry, Clock clock) {
        this.clock = clock;
        this.emittedCounter = Counter.builder(COUNTER_NAME)
                .description("Stage 0 telemetry smoke signals emitted")
                .tag("scenario", "stage0")
                .register(meterRegistry);
    }

    @PostMapping("/telemetry")
    public ResponseEntity<TelemetrySmokeResponse> emit() {
        var smokeRunId = UUID.randomUUID().toString();
        var emittedAt = clock.instant();
        var span = Span.current();
        var spanContext = span.getSpanContext();
        var traceId = traceIdOf(spanContext);
        var spanId = spanIdOf(spanContext);

        span.setAttribute("aidiag.smoke.run.id", smokeRunId);
        span.setAttribute("aidiag.smoke.scenario", "stage0");
        span.addEvent("aidiag.smoke.telemetry.emitted");
        emittedCounter.increment();

        log.atInfo()
                .addKeyValue("event", "aidiag_smoke_telemetry_emitted")
                .addKeyValue("smokeRunId", smokeRunId)
                .addKeyValue("traceId", traceId)
                .addKeyValue("spanId", spanId)
                .addKeyValue("scenario", "stage0")
                .log("Stage 0 telemetry smoke signal emitted");

        return ResponseEntity.accepted()
                .body(new TelemetrySmokeResponse(smokeRunId, traceId, emittedAt));
    }

    private static String traceIdOf(SpanContext spanContext) {
        return spanContext.isValid() ? spanContext.getTraceId() : "";
    }

    private static String spanIdOf(SpanContext spanContext) {
        return spanContext.isValid() ? spanContext.getSpanId() : "";
    }

    public record TelemetrySmokeResponse(String smokeRunId, String traceId, Instant emittedAt) {
    }
}
