package com.dduo.faultlab;

import java.util.Arrays;
import java.util.Optional;

public enum FaultType {
    DOWNSTREAM_TIMEOUT("downstream-timeout"),
    DATABASE_SLOW_QUERY("database-slow-query"),
    HIKARI_EXHAUSTION("hikari-exhaustion"),
    THREAD_POOL_EXHAUSTION("thread-pool-exhaustion");

    private final String externalName;

    FaultType(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public static Optional<FaultType> fromExternalName(String name) {
        return Arrays.stream(values()).filter(value -> value.externalName.equals(name)).findFirst();
    }
}

