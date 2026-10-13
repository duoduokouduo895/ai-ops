package com.dduo.aiops.console.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${aidiag.console.enabled:false} && '${aidiag.console.auth-mode:session}' == 'session'")
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
