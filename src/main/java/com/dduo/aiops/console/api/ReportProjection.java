package com.dduo.aiops.console.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Browser-safe diagnosis report projection. Candidates must belong to the report's incident run.
 * Only a SUPPORTED primary candidate may be copied into rootCause.
 */
final class ReportProjection {

    private static final int MAX_CANDIDATES = 3;
    private static final int MAX_LIMITATIONS = 20;
    private static final int LIMITATION_CHARS = 500;

    private ReportProjection() {
    }

    static ConsoleIncidentQueryService.ReportView project(ReportRecord report, List<CandidateRecord> candidates) {
        var inRun = new ArrayList<CandidateRecord>();
        for (var candidate : candidates) {
            if (candidate.diagnosisRunId().equals(report.diagnosisRunId()) && inRun.size() < MAX_CANDIDATES) {
                inRun.add(candidate);
            }
        }
        inRun.sort((left, right) -> Integer.compare(left.rank(), right.rank()));
        var primary = report.primaryRootCauseId() == null ? null : inRun.stream()
                .filter(candidate -> candidate.id().equals(report.primaryRootCauseId()))
                .findFirst().orElse(null);
        var confirmed = primary != null && "SUPPORTED".equals(primary.verificationStatus());
        var leading = confirmed ? primary : inRun.stream().findFirst().orElse(null);
        var disposition = disposition(confirmed, leading, inRun.isEmpty());
        var rootCause = confirmed ? bounded(primary.name(), 255) : null;
        var description = leading != null ? bounded(leading.description(), 2000)
                : "报告已生成，但没有可确认的根因。未证实候选不会作为 rootCause 返回。";
        var limitations = boundedLimitations(report.limitations());
        if (limitations.isEmpty()) {
            limitations = defaultLimitations(disposition);
        }
        return new ConsoleIncidentQueryService.ReportView(report.id(), report.generatedAt(), rootCause, description,
                leading == null ? null : leading.affectedService(),
                leading == null ? null : leading.supportScore(),
                leading == null ? null : leading.verificationStatus(),
                leading == null ? 0 : leading.supportingEvidence(),
                leading == null ? 0 : leading.opposingEvidence(),
                report.diagnosisRunId(), disposition, dispositionReason(disposition), limitations,
                inRun.stream().map(ReportProjection::candidateView).toList());
    }

    private static ConsoleIncidentQueryService.ReportCandidateView candidateView(CandidateRecord candidate) {
        return new ConsoleIncidentQueryService.ReportCandidateView(candidate.id(), candidate.rank(),
                bounded(candidate.name(), 255), bounded(candidate.description(), 2000), candidate.affectedService(),
                candidate.supportScore(), candidate.verificationStatus(), candidate.supportingEvidence(),
                candidate.opposingEvidence());
    }

    private static String disposition(boolean confirmed, CandidateRecord leading, boolean noCandidate) {
        if (confirmed) {
            return "CONFIRMED_ROOT_CAUSE";
        }
        if (noCandidate || leading == null) {
            return "NO_CANDIDATE";
        }
        return switch (leading.verificationStatus()) {
            case "WEAKLY_SUPPORTED" -> "WEAKLY_SUPPORTED";
            case "INSUFFICIENT_EVIDENCE" -> "INSUFFICIENT_EVIDENCE";
            case "REJECTED" -> "REJECTED";
            case "UNVERIFIED" -> "UNVERIFIED";
            case "SUPPORTED" -> "INCOMPLETE";
            default -> "INCOMPLETE";
        };
    }

    private static String dispositionReason(String disposition) {
        return switch (disposition) {
            case "CONFIRMED_ROOT_CAUSE" -> "Primary candidate is SUPPORTED by deterministic evidence.";
            case "WEAKLY_SUPPORTED" -> "Evidence is incomplete; the leading candidate is not a confirmed root cause.";
            case "INSUFFICIENT_EVIDENCE" -> "Evidence is insufficient to confirm a root cause.";
            case "REJECTED" -> "The leading candidate was rejected and is not a confirmed root cause.";
            case "UNVERIFIED" -> "The leading candidate is unverified and is not a confirmed root cause.";
            case "NO_CANDIDATE" -> "The report exists but no in-run candidate is available.";
            default -> "Report data is incomplete; no candidate is presented as a confirmed root cause.";
        };
    }

    private static List<String> defaultLimitations(String disposition) {
        if ("CONFIRMED_ROOT_CAUSE".equals(disposition)) {
            return List.of();
        }
        return List.of(dispositionReason(disposition), "未把未证实候选展示为确定根因", "未调用 LLM 覆盖确定性报告");
    }

    static List<String> boundedLimitations(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var bounded = new ArrayList<String>();
        for (var item : raw) {
            if (item == null) {
                continue;
            }
            var cleaned = bounded(item, LIMITATION_CHARS);
            if (cleaned != null && !cleaned.isBlank()) {
                bounded.add(cleaned);
            }
            if (bounded.size() >= MAX_LIMITATIONS) {
                break;
            }
        }
        return List.copyOf(bounded);
    }

    private static String bounded(String value, int length) {
        if (value == null) {
            return null;
        }
        var cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        return cleaned.length() <= length ? cleaned : cleaned.substring(0, length);
    }

    record ReportRecord(UUID id, Instant generatedAt, UUID diagnosisRunId, UUID primaryRootCauseId,
            List<String> limitations) { }

    record CandidateRecord(UUID id, UUID diagnosisRunId, int rank, String name, String description,
            String affectedService, Double supportScore, String verificationStatus, long supportingEvidence,
            long opposingEvidence) { }
}
