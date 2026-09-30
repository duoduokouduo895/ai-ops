package com.dduo.aiops.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class AlertmanagerWebhookControllerTests {

    private final WebhookAuthenticator authenticator = mock(WebhookAuthenticator.class);
    private final AlertmanagerWebhookService service = mock(AlertmanagerWebhookService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final WebhookIngestMetrics metrics = new WebhookIngestMetrics(meterRegistry);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T06:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsAChunkedBodyBeforeReadingBeyondTheConfiguredLimit() {
        var controller = controller(32);
        var request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent("x".repeat(33).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(authenticator.isAuthorized("Basic valid")).thenReturn(true);

        var response = controller.accept("Basic valid", request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        verify(service, never()).accept(any(), any());
        assertRejected(1);
        assertThat(metrics.lastSuccessAt()).isNull();
    }

    @Test
    void rejectsAnEmptyJsonBodyAsBadRequest() {
        var controller = controller(1024);
        var request = new MockHttpServletRequest();
        request.setContent(new byte[0]);
        when(authenticator.isAuthorized("Basic valid")).thenReturn(true);

        var response = controller.accept("Basic valid", request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(service, never()).accept(any(), any());
        assertRejected(1);
        assertThat(metrics.lastSuccessAt()).isNull();
    }

    @Test
    void rejectsUnauthorizedRequestsWithoutRefreshingSuccessTime() {
        var controller = controller(1024);
        var request = new MockHttpServletRequest();
        request.setContent("{\"receiver\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(authenticator.isAuthorized("Basic invalid")).thenReturn(false);

        var response = controller.accept("Basic invalid", request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(service, never()).accept(any(), any());
        assertRejected(1);
        assertThat(metrics.lastSuccessAt()).isNull();
    }

    @Test
    void acceptsAValidBoundedObjectAfterBusinessReceiveCompletes() {
        var controller = controller(1024);
        var request = new MockHttpServletRequest();
        request.setContent("{\"receiver\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(authenticator.isAuthorized("Basic valid")).thenReturn(true);
        when(service.accept(any(), any())).thenReturn(new AlertmanagerWebhookService.WebhookReceipt(1, 0, List.of()));

        var response = controller.accept("Basic valid", request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).accept(any(), any());
        assertThat(counter("accepted")).isEqualTo(1.0d);
        assertThat(metrics.lastSuccessAt()).isEqualTo(Instant.parse("2026-09-10T06:00:00Z"));
    }

    @Test
    void persistenceFailureDoesNotRefreshSuccessTime() {
        var controller = controller(1024);
        var request = new MockHttpServletRequest();
        request.setContent("{\"receiver\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(authenticator.isAuthorized("Basic valid")).thenReturn(true);
        when(service.accept(any(), any())).thenThrow(new DataRetrievalFailureException("database"));

        var response = controller.accept("Basic valid", request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNull();
        assertThat(counter("failed")).isEqualTo(1.0d);
        assertThat(metrics.lastSuccessAt()).isNull();
    }

    @Test
    void laterRejectionLeavesThePreviousSuccessTimeUnchanged() {
        var controller = controller(1024);
        when(authenticator.isAuthorized("Basic valid")).thenReturn(true);
        when(service.accept(any(), any())).thenReturn(new AlertmanagerWebhookService.WebhookReceipt(1, 0, List.of()));
        var accepted = new MockHttpServletRequest();
        accepted.setContent("{\"receiver\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        controller.accept("Basic valid", accepted);

        when(authenticator.isAuthorized("Basic invalid")).thenReturn(false);
        var rejected = new MockHttpServletRequest();
        var response = controller.accept("Basic invalid", rejected);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(metrics.lastSuccessAt()).isEqualTo(Instant.parse("2026-09-10T06:00:00Z"));
        assertThat(counter("rejected")).isEqualTo(1.0d);
        assertThat(counter("accepted")).isEqualTo(1.0d);
    }

    @Test
    void failsFastForAnUnsafePayloadLimit() {
        assertThatThrownBy(() -> controller(1_048_577))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-payload-bytes");
    }

    private AlertmanagerWebhookController controller(int maxPayloadBytes) {
        var environment = new MockEnvironment().withProperty("aidiag.alerts.webhook.max-payload-bytes",
                Integer.toString(maxPayloadBytes));
        return new AlertmanagerWebhookController(authenticator, service, objectMapper, environment, metrics, clock);
    }

    private void assertRejected(double expected) {
        assertThat(counter("rejected")).isEqualTo(expected);
        assertThat(counter("accepted")).isEqualTo(0.0d);
    }

    private double counter(String outcome) {
        return meterRegistry.get("aidiag.alert.webhook.ingest").tag("outcome", outcome).counter().count();
    }
}
