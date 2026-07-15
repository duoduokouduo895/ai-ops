package com.dduo.aiops.alerts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class AlertStateReconciliationWorkerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AlertmanagerWebhookService webhookService = mock(AlertmanagerWebhookService.class);
    private final AlertStateReconciliationWorker worker = new AlertStateReconciliationWorker(mock(JdbcTemplate.class),
            webhookService, objectMapper, new MockEnvironment(), new SimpleMeterRegistry(),
            Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC), mock(TransactionTemplate.class));

    @Test
    void recoversOneMissingFiringThroughTheExistingIdempotentWebhookPath() throws Exception {
        var alert = objectMapper.readTree("""
                {
                  "labels": {"alertname":"AiChatDown","service_name":"ai-chat","severity":"CRITICAL","environment":"production"},
                  "annotations": {"summary":"Health probe failed"},
                  "startsAt":"2026-07-14T23:59:00Z",
                  "endsAt":"0001-01-01T00:00:00Z",
                  "fingerprint":"reviewed-fingerprint"
                }
                """);

        worker.recoverMissingFirings(List.of(alert), List.of());

        verify(webhookService).accept(any(), any(byte[].class));
    }

    @Test
    void doesNotDuplicateAFiringThatAlreadyHasAnActiveIncident() throws Exception {
        var alert = objectMapper.readTree("""
                {"labels":{"alertname":"AiChatDown","service_name":"ai-chat"}}
                """);
        var incident = new AlertStateReconciliationWorker.IncidentIdentity(UUID.randomUUID(), "AiChatDown",
                "ai-chat", "OPEN", Instant.parse("2026-07-14T23:59:00Z"));

        worker.recoverMissingFirings(List.of(alert), List.of(incident));

        verify(webhookService, never()).accept(any(), any(byte[].class));
    }
}
