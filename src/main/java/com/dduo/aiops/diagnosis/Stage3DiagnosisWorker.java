package com.dduo.aiops.diagnosis;

import java.lang.management.ManagementFactory;

import com.dduo.aiops.work.DiagnosisRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stage 3 is deterministic: it creates an evidence-backed report and never invokes an LLM or remediation. */
@Component
@ConditionalOnProperty(prefix = "aidiag.stage3", name = "enabled", havingValue = "true")
public class Stage3DiagnosisWorker {

    private static final Logger log = LoggerFactory.getLogger(Stage3DiagnosisWorker.class);

    private final Stage3DiagnosisRepository repository;
    private final DiagnosisRunRepository runRepository;
    private final RuleBasedDiagnosisService diagnosisService;
    private final String owner;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final int retryBackoffSeconds;
    private final Counter completed;
    private final Counter stale;

    public Stage3DiagnosisWorker(Stage3DiagnosisRepository repository, DiagnosisRunRepository runRepository,
            RuleBasedDiagnosisService diagnosisService, Environment environment, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.diagnosisService = diagnosisService;
        this.owner = "aidiag-stage3-" + ManagementFactory.getRuntimeMXBean().getName();
        this.leaseSeconds = positive(environment, "aidiag.stage3.lease-seconds", 30);
        this.maxAttempts = positive(environment, "aidiag.worker.max-attempts", 3);
        this.retryBackoffSeconds = nonNegative(environment, "aidiag.worker.retry-backoff-seconds", 5);
        this.completed = meterRegistry.counter("aidiag.stage3.diagnosis", "outcome", "completed");
        this.stale = meterRegistry.counter("aidiag.stage3.diagnosis", "outcome", "stale_lease");
    }

    @Scheduled(fixedDelayString = "${aidiag.stage3.poll-delay-ms:1000}")
    public void poll() {
        repository.claimNext(owner, leaseSeconds).ifPresent(this::process);
    }

    private void process(Stage3DiagnosisRepository.ClaimedAnalysisRun run) {
        var fencedRun = new DiagnosisRunRepository.ClaimedRun(run.id(), run.incidentId(), run.attempt(), run.owner(), run.leaseToken());
        try {
            var incident = repository.incident(run.incidentId());
            var diagnosis = diagnosisService.diagnose(incident);
            if (!runRepository.heartbeat(fencedRun, leaseSeconds)) {
                stale.increment();
                return;
            }
            var receipt = repository.finalizeReport(run, incident, diagnosis);
            if (receipt.isEmpty()) {
                stale.increment();
                return;
            }
            completed.increment();
            log.atInfo().addKeyValue("event", "aidiag_stage3_report_completed").addKeyValue("diagnosisRunId", run.id())
                    .addKeyValue("reportId", receipt.get().reportId()).addKeyValue("verificationStatus", diagnosis.verificationStatus())
                    .log("Stage 3 deterministic diagnosis report completed");
        } catch (RuntimeException exception) {
            var retryScheduled = runRepository.scheduleRetry(fencedRun, maxAttempts, retryBackoffSeconds, "STAGE3_DIAGNOSIS_FAILURE",
                    exception.getMessage());
            log.atWarn().addKeyValue("event", "aidiag_stage3_diagnosis_failed").addKeyValue("diagnosisRunId", run.id())
                    .addKeyValue("retryScheduled", retryScheduled).log("Stage 3 diagnosis failed", exception);
        }
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
