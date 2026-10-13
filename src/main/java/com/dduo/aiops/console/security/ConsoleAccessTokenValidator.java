package com.dduo.aiops.console.security;

import java.time.Clock;
import java.time.Duration;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

final class ConsoleAccessTokenValidator {
    private ConsoleAccessTokenValidator() { }

    static OAuth2TokenValidator<Jwt> create(ConsoleSecuritySettings settings, Clock clock) {
        var timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            var email = jwt.getClaimAsString("email");
            var subject = jwt.getSubject();
            if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null || jwt.getNotBefore() == null
                    || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
                    || jwt.getAudience() == null || !jwt.getAudience().contains(settings.accessAudience())
                    || !"app".equals(jwt.getClaimAsString("type"))
                    || subject == null || subject.isBlank() || subject.length() > 256
                    || email == null || email.length() > 254 || !email.matches("[^\\s@\\p{Cntrl}]+@[^\\s@\\p{Cntrl}]+")) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
            return OAuth2TokenValidatorResult.success();
        };
        return new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(settings.accessIssuer()), claims);
    }
}
