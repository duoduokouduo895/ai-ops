package com.dduo.faultlab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("stage5-test")
@RestController
@RequestMapping("/internal/stage5-faults")
@ConditionalOnExpression("'${faultlab.role}' == 'ai-chat-stage5' and '${faultlab.controls-enabled}' == 'true'")
public class AiChatFaultControlController {

    static final String TOKEN_HEADER = "X-AI-Chat-Stage5-Fault-Token";
    private static final Logger OPERATIONS = LoggerFactory.getLogger("aichat.operations");

    private final AiChatFaultMetrics metrics;
    private final byte[] controlToken;

    public AiChatFaultControlController(MeterRegistry registry, Environment environment) {
        this.metrics = new AiChatFaultMetrics(registry);
        var configured = environment.getProperty("aichat.stage5.fault.token", "");
        this.controlToken = configured.length() >= 32 && configured.length() <= 512
                ? configured.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    @PostMapping("/{fault}/enable")
    public ResponseEntity<GroundTruth> enable(@PathVariable String fault,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return authorized(token) ? change(fault, true) : ResponseEntity.notFound().build();
    }

    @PostMapping("/{fault}/disable")
    public ResponseEntity<GroundTruth> disable(@PathVariable String fault,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return authorized(token) ? change(fault, false) : ResponseEntity.notFound().build();
    }

    @PostMapping("/reset")
    public ResponseEntity<GroundTruth> reset(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        for (var type : AiChatFaultType.values()) {
            metrics.setActive(type, false);
        }
        return ResponseEntity.ok(snapshot());
    }

    @GetMapping("/ground-truth")
    public ResponseEntity<GroundTruth> groundTruth(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return authorized(token) ? ResponseEntity.ok(snapshot()) : ResponseEntity.notFound().build();
    }

    private ResponseEntity<GroundTruth> change(String externalFault, boolean enabled) {
        var type = AiChatFaultType.fromExternalName(externalFault);
        if (type.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean transitioned = metrics.setActive(type.get(), enabled);
        if (enabled && transitioned) {
            emitEvidence(type.get());
        }
        OPERATIONS.atWarn().addKeyValue("event", "stage5_fault_state_changed")
                .addKeyValue("fault", type.get().externalName()).addKeyValue("enabled", enabled)
                .log("Isolated AI-chat contract fault state changed");
        return ResponseEntity.ok(snapshot());
    }

    private void emitEvidence(AiChatFaultType type) {
        var runId = UUID.randomUUID().toString();
        switch (type) {
            case MODEL_TIMEOUT -> OPERATIONS.atWarn().addKeyValue("event", "ai_chat_stream_finished")
                    .addKeyValue("result", "timeout").addKeyValue("errorType", "CHAT_TIMEOUT")
                    .addKeyValue("faultRunId", runId).log("Controlled model timeout contract event");
            case R2DBC_POOL_EXHAUSTION -> OPERATIONS.atWarn().addKeyValue("event", "r2dbc_pool_acquire")
                    .addKeyValue("result", "timeout").addKeyValue("errorType", "POOL_ACQUIRE_TIMEOUT")
                    .addKeyValue("faultRunId", runId).log("Controlled R2DBC acquisition timeout contract event");
            case DOCUMENT_BACKLOG -> OPERATIONS.atWarn().addKeyValue("event", "document_outbox_dispatch")
                    .addKeyValue("result", "retry").addKeyValue("errorType", "BROKER_PUBLISH_FAILED")
                    .addKeyValue("faultRunId", runId).log("Controlled document dispatch retry contract event");
            case SEARCH_UNAVAILABLE -> OPERATIONS.atWarn().addKeyValue("event", "knowledge_search")
                    .addKeyValue("result", "error").addKeyValue("errorType", "SEARCH_BACKEND_ERROR")
                    .addKeyValue("faultRunId", runId).log("Controlled search backend error contract event");
        }
        emitTrace(type, runId);
    }

    private void emitTrace(AiChatFaultType type, String runId) {
        var tracer = GlobalOpenTelemetry.getTracer("aidiag-stage5-fault-lab");
        var builder = tracer.spanBuilder("stage5." + type.externalName())
                .setStartTimestamp(type == AiChatFaultType.MODEL_TIMEOUT ? Instant.now().minusSeconds(11) : Instant.now());
        var span = builder.startSpan();
        try {
            span.setAttribute("aidiag.stage5.fault", type.externalName());
            span.setAttribute("aidiag.stage5.run.id", runId);
            switch (type) {
                case R2DBC_POOL_EXHAUSTION -> span.setAttribute("db.system", "mysql");
                case DOCUMENT_BACKLOG -> span.setAttribute("messaging.system", "rabbitmq");
                case SEARCH_UNAVAILABLE -> span.setAttribute("db.system", "elasticsearch");
                default -> {
                    // Model timeout is identified by its controlled duration.
                }
            }
            span.setStatus(StatusCode.ERROR);
        } finally {
            span.end(Instant.now().toEpochMilli(), TimeUnit.MILLISECONDS);
        }
    }

    private GroundTruth snapshot() {
        return new GroundTruth(metrics.snapshot(), Instant.now());
    }

    private boolean authorized(String token) {
        return controlToken.length > 0 && token != null
                && MessageDigest.isEqual(controlToken, token.getBytes(StandardCharsets.UTF_8));
    }

    public record GroundTruth(Map<String, Boolean> faults, Instant observedAt) {
    }
}
