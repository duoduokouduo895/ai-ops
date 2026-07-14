package com.dduo.aiops.aid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class Stage4AiDiagnosisServiceTests {

    private static final UUID INCIDENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID REPORT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ATTEMPT_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID EVIDENCE_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");

    private Stage4AiDiagnosisService service;

    @AfterEach
    void stopExecutor() {
        if (service != null) {
            service.stopModelExecutor();
        }
    }

    @Test
    void rejectsFabricatedEvidenceBeforeCompletingAnAdvisoryAttempt() {
        var repository = repository();
        var model = modelReturning(new AiDiagnosisAdvice("summary", List.of("hypothesis"), List.of(UUID.randomUUID().toString()),
                List.of(), List.of("uncertain")));
        service = service(repository, model, 2);

        var result = service.preview(INCIDENT_ID);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.failureCode()).isEqualTo("INVALID_MODEL_OUTPUT");
        assertThat(result.automatedRemediation()).isFalse();
        verify(repository).completeAttempt(eq(ATTEMPT_ID), eq("REJECTED"), any(), eq("INVALID_MODEL_OUTPUT"), any());
    }

    @Test
    void fallsBackWhenNoChatModelIsAvailable() {
        var repository = repository();
        var model = mock(Stage4ModelClient.class);
        when(model.provider()).thenReturn("unconfigured");
        when(model.modelName()).thenReturn("unconfigured");
        when(model.generate(any())).thenThrow(new Stage4ModelUnavailableException("No model"));
        service = service(repository, model, 2);

        var result = service.preview(INCIDENT_ID);

        assertThat(result.status()).isEqualTo("FALLBACK");
        assertThat(result.failureCode()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(result.failureReason()).isEqualTo("Model provider is unavailable");
        verify(repository).completeAttempt(eq(ATTEMPT_ID), eq("FALLBACK"), any(), eq("MODEL_UNAVAILABLE"),
                eq("Model provider is unavailable"));
    }

    @Test
    void timesOutAndInterruptsASlowModelCall() {
        var repository = repository();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var model = mock(Stage4ModelClient.class);
        when(model.provider()).thenReturn("test");
        when(model.modelName()).thenReturn("slow-test-model");
        when(model.generate(any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return new AiDiagnosisAdvice("summary", List.of("hypothesis"), List.of(), List.of(), List.of());
        });
        service = service(repository, model, 1);

        var result = service.preview(INCIDENT_ID);

        assertThat(result.failureCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(interrupted).isTrue();
        verify(repository).completeAttempt(eq(ATTEMPT_ID), eq("FALLBACK"), any(), eq("MODEL_TIMEOUT"), any());
    }

    @Test
    void disabledStageFourNeverCallsTheModel() {
        var repository = repository();
        var model = mock(Stage4ModelClient.class);
        when(model.provider()).thenReturn("unconfigured");
        when(model.modelName()).thenReturn("unconfigured");
        service = service(repository, model, 2, false);

        var result = service.preview(INCIDENT_ID);

        assertThat(result.failureCode()).isEqualTo("STAGE4_DISABLED");
        verify(model, never()).generate(any());
    }

    @Test
    void dailyBudgetStopsTheCallBeforeTheModelIsInvoked() {
        var repository = repository();
        when(repository.reserveDailyRequest(20)).thenReturn(false);
        var model = modelReturning(new AiDiagnosisAdvice("summary", List.of("hypothesis"), List.of(), List.of(), List.of()));
        service = service(repository, model, 2);

        var result = service.preview(INCIDENT_ID);

        assertThat(result.failureCode()).isEqualTo("DAILY_BUDGET_EXCEEDED");
        verify(model, never()).generate(any());
        verify(repository).completeAttempt(eq(ATTEMPT_ID), eq("FALLBACK"), any(), eq("DAILY_BUDGET_EXCEEDED"), any());
    }

    @Test
    void redactsStandaloneCredentialsAndPersonalDataBeforeCallingTheModel() {
        var repository = repository();
        var fakeApiKey = "sk-" + "1234567890abcdefghijkl";
        when(repository.evidence(RUN_ID, 12)).thenReturn(List.of(new Stage4Repository.EvidenceSnapshot(EVIDENCE_ID, "LOG",
                "loki", fakeApiKey + " cookie=abc user@example.com 13800138000 https://user:pass@example.test/path")));
        var model = modelReturning(new AiDiagnosisAdvice("summary", List.of("hypothesis"), List.of(), List.of(), List.of()));
        service = service(repository, model, 2);

        service.preview(INCIDENT_ID);

        var prompt = ArgumentCaptor.forClass(AiDiagnosisPrompt.class);
        verify(model).generate(prompt.capture());
        assertThat(prompt.getValue().evidence().getFirst().summary())
                .doesNotContain(fakeApiKey, "cookie=abc", "user@example.com", "13800138000", "user:pass")
                .contains("[REDACTED_API_KEY]", "[REDACTED_EMAIL]", "[REDACTED_PHONE]");
    }

    private static Stage4Repository repository() {
        var repository = mock(Stage4Repository.class);
        var reportJson = """
                {"candidate":"DOWNSTREAM_TIMEOUT","verificationStatus":"SUPPORTED","supportScore":65,
                 "remediationTemplate":[{"action":"Disable controlled fault"}]}
                """;
        when(repository.latestReport(INCIDENT_ID)).thenReturn(Optional.of(new Stage4Repository.ReportSnapshot(REPORT_ID, RUN_ID,
                "payment-service", "FaultLabDownstreamTimeout", "CRITICAL", reportJson)));
        when(repository.evidence(RUN_ID, 12)).thenReturn(List.of(
                new Stage4Repository.EvidenceSnapshot(EVIDENCE_ID, "METRIC", "prometheus", "bounded evidence")));
        when(repository.startAttempt(eq(INCIDENT_ID), eq(REPORT_ID), eq("stage4-v1"), any(), any(), any())).thenReturn(ATTEMPT_ID);
        when(repository.reserveDailyRequest(20)).thenReturn(true);
        return repository;
    }

    private static Stage4ModelClient modelReturning(AiDiagnosisAdvice advice) {
        var model = mock(Stage4ModelClient.class);
        when(model.provider()).thenReturn("test");
        when(model.modelName()).thenReturn("test-model");
        when(model.generate(any())).thenReturn(advice);
        return model;
    }

    private Stage4AiDiagnosisService service(Stage4Repository repository, Stage4ModelClient model, int timeoutSeconds) {
        return service(repository, model, timeoutSeconds, true);
    }

    private Stage4AiDiagnosisService service(Stage4Repository repository, Stage4ModelClient model, int timeoutSeconds,
            boolean enabled) {
        var environment = new MockEnvironment().withProperty("aidiag.stage4.enabled", Boolean.toString(enabled))
                .withProperty("aidiag.stage4.timeout-seconds", Integer.toString(timeoutSeconds));
        return new Stage4AiDiagnosisService(repository, model, new AiDiagnosisAdviceValidator(), new Stage4Settings(environment),
                new ObjectMapper());
    }
}
