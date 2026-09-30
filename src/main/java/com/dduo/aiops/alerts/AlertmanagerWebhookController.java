package com.dduo.aiops.alerts;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertmanagerWebhookController {

    private final WebhookAuthenticator authenticator;
    private final AlertmanagerWebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final WebhookIngestMetrics ingestMetrics;
    private final Clock clock;
    private final int maxPayloadBytes;

    public AlertmanagerWebhookController(WebhookAuthenticator authenticator, AlertmanagerWebhookService webhookService,
            ObjectMapper objectMapper, org.springframework.core.env.Environment environment,
            WebhookIngestMetrics ingestMetrics, Clock clock) {
        this.authenticator = authenticator;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
        this.ingestMetrics = ingestMetrics;
        this.clock = clock;
        this.maxPayloadBytes = environment.getProperty("aidiag.alerts.webhook.max-payload-bytes", Integer.class, 262_144);
        if (maxPayloadBytes <= 0 || maxPayloadBytes > 1_048_576) {
            throw new IllegalArgumentException("aidiag.alerts.webhook.max-payload-bytes must be between 1 and 1048576");
        }
    }

    @PostMapping(value = "/alertmanager", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> accept(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        if (!authenticator.isAuthorized(authorization)) {
            ingestMetrics.recordRejected();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"aidiag\"")
                    .build();
        }
        if (request.getContentLengthLong() > maxPayloadBytes) {
            ingestMetrics.recordRejected();
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(maxPayloadBytes + 1);
        } catch (IOException exception) {
            ingestMetrics.recordRejected();
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_alertmanager_payload"));
        }
        if (body.length > maxPayloadBytes) {
            ingestMetrics.recordRejected();
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (Exception exception) {
            ingestMetrics.recordRejected();
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_alertmanager_payload"));
        }
        if (payload == null || !payload.isObject()) {
            ingestMetrics.recordRejected();
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_alertmanager_payload"));
        }
        try {
            var receipt = webhookService.accept(payload, body);
            ingestMetrics.recordAccepted(clock.instant());
            return ResponseEntity.accepted().body(receipt);
        } catch (IllegalArgumentException exception) {
            ingestMetrics.recordRejected();
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_alertmanager_payload"));
        } catch (RuntimeException exception) {
            ingestMetrics.recordFailed();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
