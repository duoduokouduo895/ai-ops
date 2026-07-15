package com.dduo.aiops.console.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class ConsoleSessionServiceTests {

    private ConsoleSessionRepository repository;
    private ConsoleSessionService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ConsoleSessionRepository.class);
        var encoder = new BCryptPasswordEncoder(4);
        var environment = new MockEnvironment()
                .withProperty("aidiag.console.enabled", "true")
                .withProperty("aidiag.console.username", "aidiag-admin")
                .withProperty("aidiag.console.password-hash", encoder.encode("correct horse battery staple"))
                .withProperty("aidiag.console.audit-pepper", "0123456789abcdef0123456789abcdef");
        service = new ConsoleSessionService(repository, new ConsoleSecuritySettings(environment), encoder,
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void persistsOnlyTheOpaqueTokenHash() {
        var id = UUID.randomUUID();
        when(repository.recentFailures(any(), any(), any())).thenReturn(0);
        when(repository.create(eq("aidiag-admin"), any(), any(), any(), any(), eq(3))).thenReturn(id);

        var issued = service.login("aidiag-admin", "correct horse battery staple", "203.0.113.10", "request-1");

        var tokenHash = ArgumentCaptor.forClass(String.class);
        verify(repository).create(eq("aidiag-admin"), tokenHash.capture(), any(), any(), any(), eq(3));
        assertThat(issued.rawToken()).hasSize(43);
        assertThat(tokenHash.getValue()).hasSize(64).doesNotContain(issued.rawToken());
        assertThat(tokenHash.getValue()).doesNotContain("correct horse battery staple");
    }

    @Test
    void locksBeforePasswordVerificationAfterFiveFailures() {
        when(repository.recentFailures(any(), any(), any())).thenReturn(5);
        assertThatThrownBy(() -> service.login("aidiag-admin", "correct horse battery staple", "203.0.113.10", "r"))
                .isInstanceOf(ConsoleLoginRejectedException.class);
        verify(repository, never()).create(any(), any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void derivesAStableCsrfTokenBoundToOneSession() {
        var sessionId = UUID.randomUUID();
        var first = service.issueCsrf(sessionId);
        var second = service.issueCsrf(sessionId);
        assertThat(first).isEqualTo(second).hasSize(43);
        verify(repository, Mockito.times(2)).rotateCsrf(eq(sessionId), any());
    }
}
