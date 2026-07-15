package com.dduo.aiops.console.security;

import java.time.Clock;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/console/session")
@ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
public class ConsoleSessionController {

    private final ConsoleSessionService sessionService;
    private final ConsoleSecuritySettings settings;
    private final Clock clock;

    public ConsoleSessionController(ConsoleSessionService sessionService, ConsoleSecuritySettings settings, Clock clock) {
        this.sessionService = sessionService;
        this.settings = settings;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<SessionView> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        var requestId = ConsoleHttpSupport.requestId(request);
        var issued = sessionService.login(body.username(), body.password(), source(request), requestId);
        var csrf = sessionService.issueCsrf(issued.id());
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(issued.rawToken(), settings.absoluteDuration()).toString());
        return ResponseEntity.ok(new SessionView(issued.principal(), issued.idleExpiresAt(), issued.absoluteExpiresAt(), csrf));
    }

    @GetMapping
    SessionView current(HttpServletRequest request) {
        var session = requiredSession(request);
        return new SessionView(session.principal(), session.idleExpiresAt(), session.absoluteExpiresAt(),
                sessionService.issueCsrf(session.id()));
    }

    @DeleteMapping
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var session = requiredSession(request);
        sessionService.logout(session.id(), session.principal(), ConsoleHttpSupport.requestId(request));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", java.time.Duration.ZERO).toString());
        return ResponseEntity.noContent().build();
    }

    private ConsoleSessionRepository.SessionRecord requiredSession(HttpServletRequest request) {
        var session = (ConsoleSessionRepository.SessionRecord) request
                .getAttribute(ConsoleHttpSupport.SESSION_ATTRIBUTE);
        if (session == null || session.absoluteExpiresAt().isBefore(clock.instant())) {
            throw new ConsoleSessionExpiredException();
        }
        return session;
    }

    private ResponseCookie sessionCookie(String value, java.time.Duration maxAge) {
        return ResponseCookie.from(ConsoleHttpSupport.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(settings.secureCookie())
                .sameSite("Strict")
                .path("/api/v1/console")
                .maxAge(maxAge)
                .build();
    }

    private static String source(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    record LoginRequest(@NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 256) String password) {
    }

    public record SessionView(String principal, Instant idleExpiresAt, Instant absoluteExpiresAt, String csrfToken) {
    }
}
