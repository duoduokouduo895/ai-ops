package com.dduo.aiops.diagnosis;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Service;

/** Selects a fixed rule by exact service/alert identity and combines bounded telemetry evidence. */
@Service
public class RuleBasedDiagnosisService {

    private final DiagnosisRuleRegistry registry;
    private final TelemetryEvidenceCollector collector;

    public RuleBasedDiagnosisService(DiagnosisRuleRegistry registry, TelemetryEvidenceCollector collector) {
        this.registry = registry;
        this.collector = collector;
    }

    public DiagnosisRepository.DiagnosisResult diagnose(DiagnosisRepository.IncidentContext incident) {
        return diagnose(incident, () -> true);
    }

    DiagnosisRepository.DiagnosisResult diagnose(DiagnosisRepository.IncidentContext incident,
            BooleanSupplier renewLease) {
        var rule = registry.find(incident.primaryService(), incident.alertName()).orElse(null);
        if (rule == null) {
            return new DiagnosisRepository.DiagnosisResult("NO_DETERMINISTIC_RULE", "UNSPECIFIED",
                    "当前 service/alert 不在固定规则白名单中；报告仅记录证据不足，未查询遥测或推断根因。",
                    BigDecimal.ZERO, "INSUFFICIENT_EVIDENCE", "[]", List.of(),
                    List.of("未匹配确定性规则", "未调用 LLM", "未执行自动修复"));
        }

        var window = timeWindow(incident);
        requireCurrentLease(renewLease);
        var metric = collector.collectMetric(rule, window);
        requireCurrentLease(renewLease);
        var logs = collector.collectLogs(rule, window);
        requireCurrentLease(renewLease);
        var traces = collector.collectTraces(rule, window);
        var score = BigDecimal.ZERO;
        if (metric.matched()) {
            score = score.add(BigDecimal.valueOf(30));
        }
        if (logs.matched()) {
            score = score.add(BigDecimal.valueOf(25));
        }
        if (traces.matched()) {
            score = score.add(BigDecimal.valueOf(20));
        }

        // Trace is useful corroboration, but Stage 5 P1 must never block the Metric/Log P0 result.
        var verification = metric.matched() && logs.matched() ? "SUPPORTED"
                : metric.matched() || logs.matched() || traces.matched() ? "WEAKLY_SUPPORTED" : "INSUFFICIENT_EVIDENCE";
        var limitations = new ArrayList<String>();
        limitations.add("仅执行版本化固定 PromQL、LogQL 和 TraceQL；告警 label/annotation 不参与查询拼接");
        limitations.add("未调用 LLM，未执行自动修复");
        addFailure(limitations, metric, "Prometheus 指标");
        addFailure(limitations, logs, "Loki 日志");
        if (!traces.collected()) {
            limitations.add("Tempo Trace 采集失败或超出响应上限；已降级为 Metric/Log 判定");
        }
        if (!"SUPPORTED".equals(verification)) {
            limitations.add("Metric 与 Log 两类独立 P0 证据未同时满足，不能确认主要根因");
        }
        return new DiagnosisRepository.DiagnosisResult(rule.candidateName(), rule.groundTruth(), rule.description(), score,
                verification, rule.remediationJson(),
                List.of(metric.observation(), logs.observation(), traces.observation()), limitations);
    }

    private static void requireCurrentLease(BooleanSupplier renewLease) {
        if (!renewLease.getAsBoolean()) {
            throw new StaleDiagnosisLeaseException();
        }
    }

    static final class StaleDiagnosisLeaseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StaleDiagnosisLeaseException() {
            super("Stage 3 diagnosis lease is no longer current");
        }
    }

    private static void addFailure(List<String> limitations, TelemetryEvidenceCollector.CollectedObservation observation,
            String source) {
        if (!observation.collected()) {
            limitations.add(source + "采集失败或超出响应上限");
        }
    }

    private static TelemetryEvidenceCollector.TimeWindow timeWindow(DiagnosisRepository.IncidentContext incident) {
        var now = Instant.now();
        var start = incident.startedAt().minus(Duration.ofMinutes(10));
        var eventEnd = incident.lastAlertAt().plus(Duration.ofMinutes(5));
        var end = eventEnd.isBefore(now) ? eventEnd : now;
        return end.isAfter(start) ? new TelemetryEvidenceCollector.TimeWindow(start, end)
                : new TelemetryEvidenceCollector.TimeWindow(now.minus(Duration.ofMinutes(10)), now);
    }
}
