package com.dduo.aiops.advisory;

import java.util.List;

/** Typed, advisory-only model output. It is never written to diagnosis_report. */
public record AiDiagnosisAdvice(String summary, List<String> hypotheses, List<String> evidenceIds,
        List<String> recommendedRunbookActions, List<String> limitations) {
}
