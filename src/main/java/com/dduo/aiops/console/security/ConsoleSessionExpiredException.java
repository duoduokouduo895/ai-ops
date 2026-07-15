package com.dduo.aiops.console.security;

public final class ConsoleSessionExpiredException extends RuntimeException {
    public ConsoleSessionExpiredException() {
        super("Console session is unavailable");
    }
}
