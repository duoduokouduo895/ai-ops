package com.dduo.aiops.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class AlertmanagerWebhookControllerTests {

    private final WebhookAuthenticator authenticator = mock(WebhookAuthenticator.class);
    private final AlertmanagerWebhookService service = mock(AlertmanagerWebhookService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    }

    @Test
    void acceptsAValidBoundedObject() {
        var controller = controller(1024);
        var request = new MockHttpServletRequest();
        request.setContent("{\"receiver\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(authenticator.isAuthorized("Basic valid")).thenReturn(true);
        when(service.accept(any(), any())).thenReturn(new AlertmanagerWebhookService.WebhookReceipt(1, 0, List.of()));

        var response = controller.accept("Basic valid", request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).accept(any(), any());
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
        return new AlertmanagerWebhookController(authenticator, service, objectMapper, environment);
    }
}
