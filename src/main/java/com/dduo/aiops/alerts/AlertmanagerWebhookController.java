package com.dduo.aiops.alerts;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    }

    @PostMapping(value = "/alertmanager", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> accept(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestBody byte[] body) {
        if (!authenticator.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"aidiag\"")
                    .build();
        }
        if ((contentLength != null && contentLength > maxPayloadBytes) || body.length > maxPayloadBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        try {
            JsonNode payload = objectMapper.readTree(body);
            var receipt = webhookService.accept(payload, body);
            return ResponseEntity.accepted().body(receipt);
        } catch (IOException | IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_alertmanager_payload"));
        }
    }
}
