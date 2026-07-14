package com.dduo.aiops.alerts;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class AlertmanagerWebhookServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsAResolvedGroupThatStillContainsAFiringAlert() throws Exception {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var service = new AlertmanagerWebhookService(jdbcTemplate, objectMapper,
                new MockEnvironment().withProperty("DEPLOYMENT_ENVIRONMENT", "test"), new SimpleMeterRegistry());
        var payload = objectMapper.readTree("""
                {"receiver":"test","groupKey":"group","status":"resolved","alerts":[{
                  "status":"firing","fingerprint":"fingerprint","startsAt":"2026-07-14T00:00:00Z",
                  "labels":{"alertname":"TestAlert","service_name":"test-service","severity":"warning","environment":"test"}
                }]}
                """);

        assertThatThrownBy(() -> service.accept(payload, payload.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolved group");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void recursivelyRedactsSecretsAndPersonalDataBeforePersistence() throws Exception {
        var service = service(mock(JdbcTemplate.class));
        var fakeKey = "sk-1234567890abcdefghijkl";
        var payload = objectMapper.readTree("""
                {"authorization":"Bearer abc.def.ghi","annotations":{"summary":"KEY user@example.com 13800138000",
                "nested":["cookie=abc","https://user:pass@example.test/path"]}}
                """.replace("KEY", fakeKey));

        var sanitized = service.sanitizeForStorage(payload).toString();

        assertThat(sanitized).doesNotContain(fakeKey, "abc.def.ghi", "user@example.com", "13800138000", "cookie=abc",
                "user:pass").contains("[REDACTED]");
    }

    @Test
    void rejectsControlCharactersInIncidentIdentityLabelsBeforePersistence() throws Exception {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var service = service(jdbcTemplate);
        var payload = objectMapper.readTree("""
                {"receiver":"test","groupKey":"group","status":"firing","alerts":[{
                  "status":"firing","fingerprint":"fingerprint","startsAt":"2026-07-14T00:00:00Z",
                  "labels":{"alertname":"TestAlert","service_name":"test-service\\nforged","severity":"warning","environment":"test"}
                }]}
                """);

        assertThatThrownBy(() -> service.accept(payload, payload.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service_name");
        verifyNoInteractions(jdbcTemplate);
    }

    private AlertmanagerWebhookService service(JdbcTemplate jdbcTemplate) {
        return new AlertmanagerWebhookService(jdbcTemplate, objectMapper,
                new MockEnvironment().withProperty("DEPLOYMENT_ENVIRONMENT", "test"), new SimpleMeterRegistry());
    }
}
