package com.dduo.aiops.advisory;

import java.util.List;
import java.util.UUID;

/** Sanitized context sent to a model; raw telemetry, secrets and alert annotations are excluded. */
public record AiDiagnosisPrompt(UUID incidentId, String serviceName, String alertName, String severity,
        String deterministicCandidate, String verificationStatus, String supportScore, List<EvidenceReference> evidence,
        List<String> allowedRunbookActions) {

    public record EvidenceReference(String id, String type, String source, String summary) {
    }
}
