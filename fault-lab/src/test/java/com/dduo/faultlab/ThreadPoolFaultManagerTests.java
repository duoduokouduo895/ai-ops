package com.dduo.faultlab;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.RejectedExecutionException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ThreadPoolFaultManagerTests {

    @Test
    void rejectsPaymentWorkOnlyWhileTheControlledPoolIsSaturated() {
        var registry = new SimpleMeterRegistry();
        var manager = new ThreadPoolFaultManager(new FaultMetrics(registry));
        try {
            manager.submitPaymentWork();
            manager.enable();

            assertThatThrownBy(manager::submitPaymentWork).isInstanceOf(RejectedExecutionException.class);
        } finally {
            manager.destroy();
        }
    }
}
