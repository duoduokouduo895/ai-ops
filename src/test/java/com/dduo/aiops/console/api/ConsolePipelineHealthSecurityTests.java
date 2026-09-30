package com.dduo.aiops.console.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.dduo.aiops.catalog.AidiagSelfMonitoring;
import com.dduo.aiops.catalog.CatalogHealthService;
import com.dduo.aiops.console.security.ConsoleSessionRepository;
import com.dduo.aiops.console.security.ConsoleSecurityConfiguration;
import com.dduo.aiops.console.security.ConsoleSecurityExceptionHandler;
import com.dduo.aiops.console.security.ConsoleSecuritySettings;
import com.dduo.aiops.console.security.ConsoleSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = ConsoleApiController.class, properties = {
        "aidiag.console.enabled=true",
        "aidiag.console.password-hash=$2a$12$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "aidiag.console.audit-pepper=0123456789abcdef0123456789abcdef",
        "aidiag.console.allowed-origin=https://ops.dduo.me"
})
@Import({ConsoleSecurityConfiguration.class, ConsoleSecuritySettings.class, ConsoleApiController.class,
        ConsoleApiExceptionHandler.class, ConsoleSecurityExceptionHandler.class,
        ConsolePipelineHealthSecurityTests.TestClock.class})
class ConsolePipelineHealthSecurityTests {

    @jakarta.annotation.Resource
    MockMvc mockMvc;

    @MockitoBean
    ConsoleIncidentQueryService queryService;

    @MockitoBean
    CatalogHealthService catalogHealthService;

    @MockitoBean
    AidiagSelfMonitoring selfMonitoring;

    @MockitoBean
    ConsoleSessionService sessionService;

    @Test
    void pipelineHealthRequiresAConsoleSession() throws Exception {
        mockMvc.perform(get("/api/v1/console/pipeline-health"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CONSOLE_SESSION_REQUIRED"));
    }

    @Test
    void pipelineHealthReturnsTheCurrentSnapshotWhenSignedIn() throws Exception {
        var record = new ConsoleSessionRepository.SessionRecord(UUID.randomUUID(), "aidiag-admin",
                Instant.parse("2026-09-10T06:00:00Z"), Instant.parse("2026-09-10T06:00:00Z"),
                Instant.parse("2026-09-10T14:00:00Z"), Instant.parse("2026-09-11T06:00:00Z"));
        when(sessionService.authenticate("opaque-session-token-that-is-long-enough")).thenReturn(Optional.of(record));
        when(selfMonitoring.current()).thenReturn(AidiagSelfMonitoring.PipelineHealthView.notSampled(
                Instant.parse("2026-09-10T06:00:00Z")));

        mockMvc.perform(get("/api/v1/console/pipeline-health")
                        .header("Origin", "https://ops.dduo.me")
                        .cookie(new Cookie("AIDIAG_CONSOLE_SESSION", "opaque-session-token-that-is-long-enough")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineStatus").value("NOT_SAMPLED"))
                .andExpect(jsonPath("$.pendingRuns").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.reason").value("NOT_SAMPLED"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-10T06:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }
}
