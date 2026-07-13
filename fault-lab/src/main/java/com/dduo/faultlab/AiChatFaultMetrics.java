package com.dduo.faultlab;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Emits the bounded AI-chat telemetry contract from an isolated process only. */
public class AiChatFaultMetrics {

    private final Map<AiChatFaultType, AtomicInteger> activeFaults = new EnumMap<>(AiChatFaultType.class);
    private final AtomicInteger r2dbcPending = new AtomicInteger();
    private final AtomicInteger documentPending = new AtomicInteger();
    private final Counter modelTimeouts;
    private final Counter searchErrors;

    public AiChatFaultMetrics(MeterRegistry registry) {
        for (var type : AiChatFaultType.values()) {
            var active = new AtomicInteger();
            activeFaults.put(type, active);
            Gauge.builder("aichat.stage5.fault.active", active, AtomicInteger::get)
                    .tag("fault", type.externalName())
                    .description("Isolated Stage 5.3 control state; never a production health signal")
                    .register(registry);
        }
        Gauge.builder("aichat.r2dbc.pool.pending", r2dbcPending, AtomicInteger::get)
                .description("Controlled pending acquisitions in the isolated Stage 5.3 contract lab")
                .register(registry);
        Gauge.builder("aichat.document.outbox.pending", documentPending, AtomicInteger::get)
                .description("Controlled document backlog in the isolated Stage 5.3 contract lab")
                .register(registry);
        modelTimeouts = Counter.builder("aichat.chat.requests")
                .tag("outcome", "timeout").tag("rag", "false").register(registry);
        searchErrors = Counter.builder("aichat.search.requests")
                .tag("outcome", "error").register(registry);
    }

    public boolean setActive(AiChatFaultType type, boolean enabled) {
        var state = activeFaults.get(type);
        int previous = state.getAndSet(enabled ? 1 : 0);
        switch (type) {
            case R2DBC_POOL_EXHAUSTION -> r2dbcPending.set(enabled ? 1 : 0);
            case DOCUMENT_BACKLOG -> documentPending.set(enabled ? 10 : 0);
            default -> {
                // Counter-backed scenarios do not need a recovery gauge beyond fault.active.
            }
        }
        if (enabled && previous == 0) {
            if (type == AiChatFaultType.MODEL_TIMEOUT) {
                modelTimeouts.increment();
            } else if (type == AiChatFaultType.SEARCH_UNAVAILABLE) {
                searchErrors.increment(3D);
            }
            return true;
        }
        return false;
    }

    public Map<String, Boolean> snapshot() {
        var result = new java.util.LinkedHashMap<String, Boolean>();
        activeFaults.forEach((type, active) -> result.put(type.externalName(), active.get() == 1));
        return result;
    }
}
