package com.dduo.aiops.console.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = { ConsoleAccessSessionController.class, ConsoleSessionController.class }, properties = {
        "aidiag.console.enabled=true", "aidiag.console.auth-mode=access",
        "aidiag.console.access.team-domain=test-team.cloudflareaccess.com",
        "aidiag.console.access.audience=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
})
@Import({ConsoleSecurityConfiguration.class, ConsoleSecuritySettings.class, ConsoleAccessSessionController.class,
        ConsoleSessionController.class, ConsoleAccessSecurityBoundaryTests.TestConfiguration.class})
class ConsoleAccessSecurityBoundaryTests {
    @jakarta.annotation.Resource MockMvc mvc;
    @jakarta.annotation.Resource ApplicationContext context;
    @MockitoBean(name = "consoleAccessJwtDecoder") JwtDecoder decoder;

    private void validToken() {
        when(decoder.decode("valid-access-jwt")).thenReturn(Jwt.withTokenValue("valid-access-jwt")
                .header("alg", "RS256").subject("user-id").claim("email", "owner@example.com")
                .issuedAt(Instant.parse("2026-10-01T00:00:00Z"))
                .expiresAt(Instant.parse("2026-10-01T08:00:00Z")).build());
    }

    @Test
    void requiresJwtEvenWithForgedEmailOrAnOldSessionCookie() throws Exception {
        mvc.perform(get("/api/v1/console/session").header("Cf-Access-Authenticated-User-Email", "owner@example.com")
                .cookie(new Cookie("AIDIAG_CONSOLE_SESSION", "old-session-cookie")))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CONSOLE_ACCESS_REQUIRED"));
        assertThat(context.getBeansOfType(ConsoleSessionController.class)).isEmpty();
    }

    @Test
    void returnsValidatedIdentityWithoutIssuingALocalCookie() throws Exception {
        validToken();
        mvc.perform(get("/api/v1/console/session").header("Cf-Access-Jwt-Assertion", "valid-access-jwt")
                .header("Cf-Access-Authenticated-User-Email", "attacker@example.com"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.principal").value("owner@example.com"))
                .andExpect(jsonPath("$.authMode").value("access")).andExpect(jsonPath("$.csrfToken").value(""))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void rejectsInvalidDuplicateAndOversizedTokens() throws Exception {
        when(decoder.decode("bad-token")).thenThrow(new BadJwtException("private decoder details"));
        mvc.perform(get("/api/v1/console/session").header("Cf-Access-Jwt-Assertion", "bad-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("请通过 Cloudflare 账号登录"));
        mvc.perform(get("/api/v1/console/session").header("Cf-Access-Jwt-Assertion", "a", "b"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/console/session").header("Cf-Access-Jwt-Assertion", "a".repeat(16385)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disablesPasswordLoginAndLocalLogoutEvenForAnAuthenticatedAccessUser() throws Exception {
        validToken();
        mvc.perform(post("/api/v1/console/session").header("Cf-Access-Jwt-Assertion", "valid-access-jwt"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("CONSOLE_ACCESS_READ_ONLY"));
        mvc.perform(delete("/api/v1/console/session").header("Cf-Access-Jwt-Assertion", "valid-access-jwt"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void allowsReadOnlyOriginRequestsButRejectsWritePreflight() throws Exception {
        validToken();
        mvc.perform(head("/api/v1/console/session").header("Cf-Access-Jwt-Assertion", "valid-access-jwt")
                .header("Origin", "https://ops.dduo.me")).andExpect(status().isOk());
        mvc.perform(options("/api/v1/console/session").header("Origin", "https://ops.dduo.me")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }

    @Test
    void doesNotTakeOverMachineWebhookOrHealthRoutes() throws Exception {
        mvc.perform(post("/api/v1/alerts/alertmanager")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestConfiguration {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
