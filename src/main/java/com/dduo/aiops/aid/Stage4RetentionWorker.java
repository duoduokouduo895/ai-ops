package com.dduo.aiops.aid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes only expired Stage 4 advisory audit data; deterministic reports are untouched. */
@Component
@ConditionalOnProperty(prefix = "aidiag.stage4", name = "enabled", havingValue = "true")
class Stage4RetentionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(Stage4RetentionWorker.class);

    private final Stage4Repository repository;
    private final Stage4Settings settings;

    Stage4RetentionWorker(Stage4Repository repository, Stage4Settings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    @Scheduled(initialDelayString = "${aidiag.stage4.cleanup-initial-delay:PT1M}",
            fixedDelayString = "${aidiag.stage4.cleanup-delay:PT24H}")
    void purgeExpiredData() {
        var deleted = repository.purgeExpiredData(settings.retentionDays());
        if (deleted > 0) {
            LOGGER.info("event=\"aidiag_stage4_audit_purged\" deletedAttempts={}", deleted);
        }
    }
}
