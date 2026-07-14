package com.dduo.aiops.alerts;

import java.io.IOException;
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
    private final int maxPayloadBytes;

    public AlertmanagerWebhookController(WebhookAuthenticator authenticator, AlertmanagerWebhookService webhookService,
            ObjectMapper objectMapper, org.springframework.core.env.Environment environment) {
        this.authenticator = authenticator;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
        this.maxPayloadBytes = environment.getProperty("aidiag.alerts.webhook.max-payload-bytes", Integer.class, 262_144);
        if (maxPayloadBytes <= 0 || maxPayloadBytes > 1_048_576) {
            throw new IllegalArgumentException("aidiag.alerts.webhook.max-payload-bytes must be between 1 and 1048576");
        }
    }

    @PostMapping(value = "/alertmanager", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> accept(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        if (!authenticator.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"aidiag\"")
                    .build();
        }
        if (request.getContentLengthLong() > maxPayloadBytes) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        try {
            var body = request.getInputStream().readNBytes(maxPayloadBytes + 1);
            if (body.length > maxPayloadBytes) {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
            }
            JsonNode payload = objectMapper.readTree(body);
            if (payload == null || !payload.isObject()) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid_alertmanager_payload"));
            }
            var receipt = webhookService.accept(payload, body);
            return ResponseEntity.accepted().body(receipt);
        } catch (IOException | IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_alertmanager_payload"));
        }
    }
}
