package com.dduo.aiops.aid;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Rejects unbounded or unsupported model claims before they become an advisory record. */
@Component
public class AiDiagnosisAdviceValidator {

    private static final int MAX_HYPOTHESES = 3;
    private static final int MAX_LIMITATIONS = 3;
    private static final int MAX_TEXT_LENGTH = 600;

    public ValidationResult validate(AiDiagnosisAdvice advice, Set<String> allowedEvidenceIds,
            Set<String> allowedRunbookActions) {
        if (advice == null || !validText(advice.summary())) {
            return ValidationResult.rejected("summary is missing or too long");
        }
        if (!validTextList(advice.hypotheses(), 1, MAX_HYPOTHESES)) {
            return ValidationResult.rejected("hypotheses must contain one to three bounded items");
        }
        if (!validTextList(advice.limitations(), 0, MAX_LIMITATIONS)) {
            return ValidationResult.rejected("limitations contain an invalid item");
        }
        var evidenceIds = safeList(advice.evidenceIds());
        if (evidenceIds.size() > allowedEvidenceIds.size() || new LinkedHashSet<>(evidenceIds).size() != evidenceIds.size()
                || evidenceIds.stream().anyMatch(id -> !allowedEvidenceIds.contains(id))) {
            return ValidationResult.rejected("response references evidence outside the supplied allowlist");
        }
        var actions = safeList(advice.recommendedRunbookActions());
        if (actions.size() > allowedRunbookActions.size() || new LinkedHashSet<>(actions).size() != actions.size()
                || actions.stream().anyMatch(action -> !allowedRunbookActions.contains(action))) {
            return ValidationResult.rejected("response proposes an action outside the controlled runbook allowlist");
        }
        return ValidationResult.accepted(new AiDiagnosisAdvice(advice.summary().trim(), normalized(advice.hypotheses()),
                normalized(evidenceIds), normalized(actions), normalized(advice.limitations())));
    }

    private static boolean validTextList(List<String> values, int minimum, int maximum) {
        var safe = safeList(values);
        return safe.size() >= minimum && safe.size() <= maximum && safe.stream().allMatch(AiDiagnosisAdviceValidator::validText)
                && new LinkedHashSet<>(safe).size() == safe.size();
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.trim().length() <= MAX_TEXT_LENGTH;
    }

    private static List<String> normalized(List<String> values) {
        return safeList(values).stream().map(String::trim).toList();
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    public record ValidationResult(AiDiagnosisAdvice advice, String rejectionReason) {
        static ValidationResult accepted(AiDiagnosisAdvice advice) {
            return new ValidationResult(advice, null);
        }

        static ValidationResult rejected(String reason) {
            return new ValidationResult(null, reason);
        }

        public boolean accepted() {
            return advice != null;
        }
    }
}
