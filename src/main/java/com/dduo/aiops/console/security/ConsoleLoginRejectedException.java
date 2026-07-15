package com.dduo.aiops.console.security;

public final class ConsoleLoginRejectedException extends RuntimeException {

    private final boolean locked;

    public ConsoleLoginRejectedException(boolean locked) {
        super("Console login rejected");
        this.locked = locked;
    }

    public boolean locked() {
        return locked;
    }
}
