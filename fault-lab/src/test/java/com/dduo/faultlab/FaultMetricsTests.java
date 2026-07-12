package com.dduo.faultlab;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class FaultMetricsTests {

    @Test
    void registersThreadPoolCounterBeforeTheFirstRejection() {
        var registry = new SimpleMeterRegistry();
        var metrics = new FaultMetrics(registry);

        var counter = registry.find("faultlab.thread.pool.rejected")
                .tag("fault", FaultType.THREAD_POOL_EXHAUSTION.externalName())
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isZero();
        metrics.recordThreadPoolRejected();
        assertThat(counter.count()).isEqualTo(1D);
    }

    @Test
    void exposesOnlyTheFixedFaultLabThreadPoolSaturationState() {
        var registry = new SimpleMeterRegistry();
        var metrics = new FaultMetrics(registry);

        metrics.setThreadPoolState(2, 1);
        assertThat(registry.get("faultlab.thread.pool.saturated").gauge().value()).isZero();

        metrics.setThreadPoolState(2, 2);
        assertThat(registry.get("faultlab.thread.pool.saturated").gauge().value()).isEqualTo(1D);

        metrics.setFaultActive(FaultType.DOWNSTREAM_TIMEOUT, true);
        assertThat(registry.find("faultlab.fault.active").tag("fault", "downstream-timeout").gauge().value()).isEqualTo(1D);
    }
}
