package com.dduo.faultlab;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class FaultMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger hikariHolders = new AtomicInteger();
    private final AtomicInteger threadPoolActive = new AtomicInteger();
    private final AtomicInteger threadPoolQueueDepth = new AtomicInteger();
    private final AtomicInteger threadPoolSaturated = new AtomicInteger();
    private final Map<FaultType, AtomicInteger> activeFaults = new EnumMap<>(FaultType.class);
    private final Counter threadPoolRejected;

    public FaultMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("faultlab.hikari.holders.active", hikariHolders, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("faultlab.thread.pool.active", threadPoolActive, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("faultlab.thread.pool.queue.depth", threadPoolQueueDepth, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("faultlab.thread.pool.saturated", threadPoolSaturated, AtomicInteger::get).register(meterRegistry);
        for (var type : FaultType.values()) {
            var active = new AtomicInteger();
            activeFaults.put(type, active);
            Gauge.builder("faultlab.fault.active", active, AtomicInteger::get)
                    .tag("fault", type.externalName())
                    .description("Controlled Fault Lab state only; not a production business metric")
                    .register(meterRegistry);
        }
        // Register before any rejection happens. Otherwise Prometheus can see its first sample
        // after the counter has already increased and treat that value as the baseline.
        this.threadPoolRejected = Counter.builder("faultlab.thread.pool.rejected")
                .tag("fault", FaultType.THREAD_POOL_EXHAUSTION.externalName())
                .register(meterRegistry);
    }

    public void recordInjection(FaultType type, boolean enabled) {
        meterRegistry.counter("faultlab.fault.injection", "fault", type.externalName(), "state", enabled ? "enabled" : "disabled")
                .increment();
    }

    public void recordThreadPoolRejected() {
        threadPoolRejected.increment();
    }

    public void setHikariHolders(int value) {
        hikariHolders.set(value);
    }

    public void setThreadPoolState(int active, int queueDepth) {
        threadPoolActive.set(active);
        threadPoolQueueDepth.set(queueDepth);
        // The Fault Lab executor is deliberately fixed at two active workers and two queued tasks.
        threadPoolSaturated.set(active >= 2 && queueDepth >= 2 ? 1 : 0);
    }

    public void setFaultActive(FaultType type, boolean active) {
        activeFaults.get(type).set(active ? 1 : 0);
    }
}
