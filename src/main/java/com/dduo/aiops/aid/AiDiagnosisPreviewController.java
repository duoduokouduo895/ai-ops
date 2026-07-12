package com.dduo.aiops.aid;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit, operator-triggered preview endpoint; it has no automatic remediation path. */
@RestController
@RequestMapping("/api/v1/incidents")
@ConditionalOnProperty(prefix = "aidiag.stage4", name = "enabled", havingValue = "true")
public class AiDiagnosisPreviewController {

    private final Stage4AiDiagnosisService service;
    private final Stage4OperatorAuthenticator authenticator;

    public AiDiagnosisPreviewController(Stage4AiDiagnosisService service, Stage4OperatorAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping("/{incidentId}/ai-diagnosis-preview")
    public ResponseEntity<Stage4AiDiagnosisService.PreviewResult> preview(
            @org.springframework.web.bind.annotation.RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID incidentId) {
        if (!authenticator.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"aidiag-stage4\"").build();
        }
        try {
            var result = service.preview(incidentId);
            return ResponseEntity.status(statusFor(result.status())).body(result);
        } catch (NoSuchElementException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    private static HttpStatus statusFor(String status) {
        return switch (status) {
            case "COMPLETED" -> HttpStatus.OK;
            case "REJECTED" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
