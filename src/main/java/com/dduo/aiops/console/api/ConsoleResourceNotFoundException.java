package com.dduo.aiops.console.api;

final class ConsoleResourceNotFoundException extends RuntimeException {
    ConsoleResourceNotFoundException(String resource) {
        super(resource);
    }
}
