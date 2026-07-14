package com.dduo.aiops.aid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/** Produces an auditable advisory preview without mutating the deterministic diagnosis report. */
@Service
public class Stage4AiDiagnosisService {

    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|token|api[_-]?key|secret|authorization|cookie|session)\\b\\s*[:=]\\s*[^\\s,;]+",
            Pattern.UNICODE_CASE);
    private static final Pattern STANDALONE_API_KEY = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{16,}\\b");
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)\\b(https?://)[^\\s/@:]+:[^\\s/@]+@");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern CN_MOBILE = Pattern.compile("(?<![A-Za-z0-9])(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final int MAX_RUNBOOK_ACTIONS = 5;

    private final Stage4Repository repository;
    private final Stage4ModelClient modelClient;
    private final AiDiagnosisAdviceValidator validator;
    private final Stage4Settings settings;
    private final ObjectMapper objectMapper;
    private final ExecutorService modelExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public Stage4AiDiagnosisService(Stage4Repository repository, Stage4ModelClient modelClient,
            AiDiagnosisAdviceValidator validator, Stage4Settings settings, ObjectMapper objectMapper) {
        this.repository = repository;
        this.modelClient = modelClient;
        this.validator = validator;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    public PreviewResult preview(UUID incidentId) {
        var snapshot = repository.latestReport(incidentId)
                .orElseThrow(() -> new NoSuchElementException("No deterministic diagnosis report exists for incident " + incidentId));
        var prompt = prompt(incidentId, snapshot);
        var requestJson = json(prompt);
        var attemptId = repository.startAttempt(incidentId, snapshot.reportId(), settings.promptVersion(), requestJson,
                modelClient.provider(), modelClient.modelName());
        if (!settings.enabled()) {
            return fallback(attemptId, "STAGE4_DISABLED", "Stage 4 is disabled by configuration");
        }
        if (requestJson.length() > settings.maxPromptChars()) {
            return fallback(attemptId, "PROMPT_BUDGET_EXCEEDED", "Sanitized model prompt exceeds the configured size limit");
        }
        if (!repository.reserveDailyRequest(settings.dailyRequestLimit())) {
            return fallback(attemptId, "DAILY_BUDGET_EXCEEDED", "Daily model request limit has been reached");
        }
        try {
            var advice = generateWithinBudget(prompt);
            var result = validator.validate(advice, evidenceIds(prompt), new LinkedHashSet<>(prompt.allowedRunbookActions()));
            if (!result.accepted()) {
                var response = MapBuilder.response(null, result.rejectionReason());
                repository.completeAttempt(attemptId, "REJECTED", json(response), "INVALID_MODEL_OUTPUT", result.rejectionReason());
                return new PreviewResult(attemptId, "REJECTED", null, "INVALID_MODEL_OUTPUT", result.rejectionReason(), false);
            }
            repository.completeAttempt(attemptId, "COMPLETED", json(MapBuilder.response(result.advice(), null)), null, null);
            return new PreviewResult(attemptId, "COMPLETED", result.advice(), null, null, false);
        } catch (Stage4ModelUnavailableException exception) {
            return fallback(attemptId, "MODEL_UNAVAILABLE", "Model provider is unavailable");
        } catch (TimeoutException exception) {
            return fallback(attemptId, "MODEL_TIMEOUT", "Model response exceeded " + settings.timeoutSeconds() + " seconds");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fallback(attemptId, "MODEL_INTERRUPTED", "Model request was interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Stage4ModelUnavailableException unavailable) {
                return fallback(attemptId, "MODEL_UNAVAILABLE", "Model provider is unavailable");
            }
            return fallback(attemptId, "MODEL_FAILURE", "Model request failed");
        } catch (RuntimeException exception) {
            return fallback(attemptId, "MODEL_FAILURE", "Model request failed");
        }
    }

    private AiDiagnosisAdvice generateWithinBudget(AiDiagnosisPrompt prompt)
            throws InterruptedException, ExecutionException, TimeoutException {
        var response = modelExecutor.submit(() -> modelClient.generate(prompt));
        try {
            return response.get(settings.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            response.cancel(true);
            throw exception;
        }
    }

    private PreviewResult fallback(UUID attemptId, String failureCode, String reason) {
        repository.completeAttempt(attemptId, "FALLBACK", json(MapBuilder.response(null, reason)), failureCode, reason);
        return new PreviewResult(attemptId, "FALLBACK", null, failureCode, reason, false);
    }

    private AiDiagnosisPrompt prompt(UUID incidentId, Stage4Repository.ReportSnapshot snapshot) {
        try {
            JsonNode report = objectMapper.readTree(snapshot.reportJson());
            var evidence = repository.evidence(snapshot.diagnosisRunId(), settings.maxEvidence()).stream()
                    .map(item -> new AiDiagnosisPrompt.EvidenceReference(item.id().toString(), bounded(item.type(), 64),
                            bounded(item.source(), 128), sanitizedEvidenceSummary(item.summary())))
                    .toList();
            return new AiDiagnosisPrompt(incidentId, bounded(snapshot.serviceName(), 128), bounded(snapshot.alertName(), 128),
                    bounded(snapshot.severity(), 32), bounded(report.path("candidate").asText("UNKNOWN"), 255),
                    bounded(report.path("verificationStatus").asText("UNKNOWN"), 32), bounded(report.path("supportScore").asText("0"), 16),
                    evidence, controlledActions(report.path("remediationTemplate")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored deterministic report is not valid JSON", exception);
        }
    }

    private static List<String> controlledActions(JsonNode remediation) {
        var actions = new ArrayList<String>();
        if (remediation.isArray()) {
            for (JsonNode item : remediation) {
                var action = bounded(item.path("action").asText(null), 600);
                if (!action.isBlank() && !actions.contains(action) && actions.size() < MAX_RUNBOOK_ACTIONS) {
                    actions.add(action);
                }
            }
        }
        return List.copyOf(actions);
    }

    private static Set<String> evidenceIds(AiDiagnosisPrompt prompt) {
        var ids = new LinkedHashSet<String>();
        prompt.evidence().forEach(item -> ids.add(item.id()));
        return ids;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Stage 4 audit data", exception);
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        var cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private static String sanitizedEvidenceSummary(String value) {
        var sanitized = BEARER_SECRET.matcher(value == null ? "" : value).replaceAll("Bearer [REDACTED]");
        sanitized = ASSIGNED_SECRET.matcher(sanitized).replaceAll("$1=[REDACTED]");
        sanitized = STANDALONE_API_KEY.matcher(sanitized).replaceAll("[REDACTED_API_KEY]");
        sanitized = URI_USER_INFO.matcher(sanitized).replaceAll("$1[REDACTED]@");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[REDACTED_EMAIL]");
        sanitized = CN_MOBILE.matcher(sanitized).replaceAll("[REDACTED_PHONE]");
        return bounded(sanitized, 600);
    }

    @PreDestroy
    void stopModelExecutor() {
        modelExecutor.close();
    }

    public record PreviewResult(UUID attemptId, String status, AiDiagnosisAdvice advice, String failureCode,
            String failureReason, boolean automatedRemediation) {
    }

    private static final class MapBuilder {
        private MapBuilder() {
        }

        static java.util.Map<String, Object> response(AiDiagnosisAdvice advice, String note) {
            var response = new LinkedHashMap<String, Object>();
            response.put("advice", advice);
            response.put("note", note);
            response.put("automatedRemediation", false);
            response.put("primaryRootCausePreserved", true);
            return response;
        }
    }
}
