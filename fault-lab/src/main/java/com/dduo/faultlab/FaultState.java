package com.dduo.faultlab;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class FaultState {

    private final Map<FaultType, Boolean> states = new EnumMap<>(FaultType.class);
    private Instant lastChangedAt = Instant.EPOCH;

    public FaultState() {
        for (var type : FaultType.values()) {
            states.put(type, false);
        }
    }

    public synchronized boolean isEnabled(FaultType type) {
        return states.get(type);
    }

    public synchronized void setEnabled(FaultType type, boolean enabled) {
        states.put(type, enabled);
        lastChangedAt = Instant.now();
    }

    public synchronized Map<FaultType, Boolean> snapshot() {
        return Map.copyOf(states);
    }

    public synchronized Instant lastChangedAt() {
        return lastChangedAt;
    }
}

