package com.dduo.aiops.monitoring;

import java.time.Duration;

/** Fixed query windows. Each keeps a series at or below 121 points. */
public enum SignalRange {
    ONE_HOUR("1h", Duration.ofHours(1), Duration.ofSeconds(30)),
    SIX_HOURS("6h", Duration.ofHours(6), Duration.ofMinutes(3)),
    ONE_DAY("24h", Duration.ofHours(24), Duration.ofMinutes(12));

    public static final String PATTERN = "1h|6h|24h";

    private final String code;
    private final Duration window;
    private final Duration step;

    SignalRange(String code, Duration window, Duration step) {
        this.code = code;
        this.window = window;
        this.step = step;
    }

    public static SignalRange fromCode(String code) {
        for (var range : values()) {
            if (range.code.equals(code)) {
                return range;
            }
        }
        throw new IllegalArgumentException("unsupported range");
    }

    public String code() {
        return code;
    }

    public Duration window() {
        return window;
    }

    public Duration step() {
        return step;
    }
}
