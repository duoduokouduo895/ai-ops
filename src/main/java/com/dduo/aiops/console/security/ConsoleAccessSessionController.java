package com.dduo.aiops.console.security;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExpression("${aidiag.console.enabled:false} && '${aidiag.console.auth-mode:session}' == 'access'")
public class ConsoleAccessSessionController {
    @GetMapping("/api/v1/console/session")
    public AccessSessionView current(HttpServletRequest request) {
        var jwt = (Jwt) request.getAttribute(ConsoleAccessAuthenticationFilter.JWT_ATTRIBUTE);
        return new AccessSessionView(jwt.getClaimAsString("email"), jwt.getExpiresAt(), jwt.getExpiresAt(), "", "access");
    }

    public record AccessSessionView(String principal, Instant idleExpiresAt, Instant absoluteExpiresAt,
            String csrfToken, String authMode) { }
}
