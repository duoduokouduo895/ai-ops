package com.dduo.aiops.console.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ConsoleSessionController.class, properties = {
        "aidiag.console.enabled=true",
        "aidiag.console.password-hash=$2a$12$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "aidiag.console.audit-pepper=0123456789abcdef0123456789abcdef",
        "aidiag.console.allowed-origin=https://ops.dduo.me"
})
@Import({ConsoleSecurityConfiguration.class, ConsoleSecuritySettings.class,
        ConsoleSessionController.class, ConsoleSecurityExceptionHandler.class,
        ConsoleSecurityBoundaryTests.TestClock.class})
class ConsoleSecurityBoundaryTests {

    @jakarta.annotation.Resource
    MockMvc mockMvc;

    @MockitoBean
    ConsoleSessionService sessionService;

    @Test
    void rejectsUnauthenticatedConsoleReadsWithUniformNoStoreError() throws Exception {
        mockMvc.perform(get("/api/v1/console/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CONSOLE_SESSION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void permitsCredentialedPreflightOnlyFromTheReviewedConsoleOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/console/session")
                        .header("Origin", "https://ops.dduo.me")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-aidiag-csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://ops.dduo.me"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void rejectsLoginFromAnUnreviewedOrigin() throws Exception {
        mockMvc.perform(post("/api/v1/console/session").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"aidiag-admin\",\"password\":\"secret\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSOLE_ORIGIN_REJECTED"));
    }

    @Test
    void rejectsOversizedLoginBeforeJsonDeserialization() throws Exception {
        mockMvc.perform(post("/api/v1/console/session").header("Origin", "https://ops.dduo.me")
                        .contentType(MediaType.APPLICATION_JSON).content("x".repeat(4097)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("CONSOLE_PAYLOAD_TOO_LARGE"));
    }

    @Test
    void rejectsMalformedLoginWithUniformNoStoreError() throws Exception {
        mockMvc.perform(post("/api/v1/console/session").header("Origin", "https://ops.dduo.me")
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CONSOLE_INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void requiresSessionBoundCsrfForLogout() throws Exception {
        var record = new ConsoleSessionRepository.SessionRecord(UUID.randomUUID(), "aidiag-admin",
                Instant.parse("2026-07-15T00:00:00Z"), Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T08:00:00Z"), Instant.parse("2026-07-16T00:00:00Z"));
        when(sessionService.authenticate("opaque-session-token-that-is-long-enough")).thenReturn(Optional.of(record));
        when(sessionService.csrfMatches(any(), any())).thenReturn(false);

        mockMvc.perform(delete("/api/v1/console/session").header("Origin", "https://ops.dduo.me")
                        .header("X-AIDiag-CSRF", "wrong-token-that-is-long-enough-for-validation")
                        .cookie(new Cookie("AIDIAG_CONSOLE_SESSION", "opaque-session-token-that-is-long-enough")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSOLE_CSRF_REJECTED"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }
}
