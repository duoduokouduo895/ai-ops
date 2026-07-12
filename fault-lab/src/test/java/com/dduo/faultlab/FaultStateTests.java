package com.dduo.faultlab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FaultStateTests {

    @Test
    void defaultsEveryFixedFaultToDisabled() {
        var state = new FaultState();

        assertThat(state.snapshot())
                .containsOnlyKeys(FaultType.values())
                .containsEntry(FaultType.DOWNSTREAM_TIMEOUT, false)
                .containsEntry(FaultType.DATABASE_SLOW_QUERY, false)
                .containsEntry(FaultType.HIKARI_EXHAUSTION, false)
                .containsEntry(FaultType.THREAD_POOL_EXHAUSTION, false);
    }
}
