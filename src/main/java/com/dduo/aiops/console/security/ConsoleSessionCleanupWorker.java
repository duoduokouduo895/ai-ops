package com.dduo.aiops.console.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
public class ConsoleSessionCleanupWorker {

    private final ConsoleSessionRepository repository;
    private final ConsoleSecuritySettings settings;

    public ConsoleSessionCleanupWorker(ConsoleSessionRepository repository, ConsoleSecuritySettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    @Scheduled(initialDelayString = "${aidiag.console.cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${aidiag.console.cleanup-delay-ms:21600000}")
    public void purgeExpiredSecurityData() {
        repository.purge(settings.retentionDays());
    }
}
