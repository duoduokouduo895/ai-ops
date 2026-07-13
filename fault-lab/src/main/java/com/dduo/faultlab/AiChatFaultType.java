package com.dduo.faultlab;

import java.util.Arrays;
import java.util.Optional;

/** Fixed Stage 5.3 contract scenarios; external input can never create a new metric label. */
public enum AiChatFaultType {
    MODEL_TIMEOUT("model-timeout"),
    R2DBC_POOL_EXHAUSTION("r2dbc-pool-exhaustion"),
    DOCUMENT_BACKLOG("document-backlog"),
    SEARCH_UNAVAILABLE("search-unavailable");

    private final String externalName;

    AiChatFaultType(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public static Optional<AiChatFaultType> fromExternalName(String value) {
        return Arrays.stream(values()).filter(type -> type.externalName.equals(value)).findFirst();
    }
}
