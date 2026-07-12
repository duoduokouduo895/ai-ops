package com.dduo.aiops.work;

import java.lang.management.ManagementFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stage 2 owns durable delivery only; Stage 3 owns diagnosis and report generation. */
@Component
@ConditionalOnProperty(prefix = "aidiag.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DiagnosisRunWorker {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisRunWorker.class);

    private final DiagnosisRunRepository repository;
    private final String owner;
    private final int leaseSeconds;
    private final int heartbeatSeconds;
    private final int processingDelayMs;
    private final int maxAttempts;
    private final int retryBackoffSeconds;
    private final int stageThreeInitialDelaySeconds;
    private final Counter claimedCounter;
    private final Counter staleCounter;

    public DiagnosisRunWorker(DiagnosisRunRepository repository, Environment environment, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.owner = "aidiag-" + ManagementFactory.getRuntimeMXBean().getName();
        this.leaseSeconds = positive(environment, "aidiag.worker.lease-seconds", 30);
        this.heartbeatSeconds = positive(environment, "aidiag.worker.heartbeat-seconds", 5);
        if (heartbeatSeconds >= leaseSeconds) {
            throw new IllegalArgumentException("aidiag.worker.heartbeat-seconds must be smaller than lease-seconds");
        }
        this.processingDelayMs = nonNegative(environment, "aidiag.worker.processing-delay-ms", 0);
        this.maxAttempts = positive(environment, "aidiag.worker.max-attempts", 3);
        this.retryBackoffSeconds = nonNegative(environment, "aidiag.worker.retry-backoff-seconds", 5);
        this.stageThreeInitialDelaySeconds = nonNegative(environment, "aidiag.stage3.initial-delay-seconds", 30);
        this.claimedCounter = meterRegistry.counter("aidiag.diagnosis.runs", "outcome", "claimed");
        this.staleCounter = meterRegistry.counter("aidiag.diagnosis.runs", "outcome", "stale_lease");
    }

    @Scheduled(fixedDelayString = "${aidiag.worker.poll-delay-ms:1000}")
    public void poll() {
        repository.claimNext(owner, leaseSeconds).ifPresent(this::process);
    }

    private void process(DiagnosisRunRepository.ClaimedRun run) {
        claimedCounter.increment();
        try {
            if (!repository.moveToCollecting(run) || !waitWithHeartbeats(run)
                    || !repository.recordStageTwoHandoff(run, stageThreeInitialDelaySeconds)) {
                staleCounter.increment();
                return;
            }
            log.atInfo().addKeyValue("event", "aidiag_diagnosis_run_evidence_ready").addKeyValue("diagnosisRunId", run.id())
                    .addKeyValue("incidentId", run.incidentId()).log("Stage 2 diagnosis run reached evidence handoff");
        } catch (RuntimeException exception) {
            var scheduled = repository.scheduleRetry(run, maxAttempts, retryBackoffSeconds, "STAGE2_WORKER_FAILURE",
                    exception.getMessage());
            log.atWarn().addKeyValue("event", "aidiag_diagnosis_run_failed").addKeyValue("diagnosisRunId", run.id())
                    .addKeyValue("retryScheduled", scheduled).log("Stage 2 diagnosis worker failed", exception);
        }
    }

    private boolean waitWithHeartbeats(DiagnosisRunRepository.ClaimedRun run) {
        var remaining = processingDelayMs;
        while (remaining > 0) {
            var sleepMs = Math.min(remaining, heartbeatSeconds * 1_000);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("worker interrupted", exception);
            }
            remaining -= sleepMs;
            if (remaining > 0 && !repository.heartbeat(run, leaseSeconds)) {
                return false;
            }
        }
        return true;
    }

    private static int positive(Environment environment, String property, int defaultValue) {
        var value = environment.getProperty(property, Integer.class, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }

    private static int nonNegative(Environment environment, String property, int defaultValue) {
        var value = environment.getProperty(property, Integer.class, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(property + " must not be negative");
        }
        return value;
    }
}
