package com.dduo.aiops.console.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues opaque browser sessions while keeping every raw credential out of PostgreSQL and logs. */
@Service
@ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
public class ConsoleSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConsoleSessionRepository repository;
    private final ConsoleSecuritySettings settings;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public ConsoleSessionService(ConsoleSessionRepository repository, ConsoleSecuritySettings settings,
            PasswordEncoder passwordEncoder, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public IssuedSession login(String username, String password, String source, String requestId) {
        var normalizedUsername = bounded(username, 128);
        var principalHash = hmac("principal:" + normalizedUsername.toLowerCase(java.util.Locale.ROOT));
        var sourceHash = hmac("source:" + bounded(source, 256) + ":" + principalHash);
        var now = clock.instant();
        repository.lockLoginKey(principalHash, sourceHash);
        if (repository.recentFailures(principalHash, sourceHash, now.minus(settings.lockoutDuration())) >= settings.maxFailures()) {
            repository.recordLoginAttempt(principalHash, sourceHash, "LOCKED");
            repository.audit(null, null, "LOGIN", "REJECTED", requestId);
            throw new ConsoleLoginRejectedException(true);
        }

        var expectedPrincipalHash = hmac("principal:" + settings.username().toLowerCase(java.util.Locale.ROOT));
        var usernameMatches = constantTimeEquals(principalHash, expectedPrincipalHash);
        var passwordMatches = password != null && passwordEncoder.matches(password, settings.passwordHash());
        if (!usernameMatches || !passwordMatches) {
            repository.recordLoginAttempt(principalHash, sourceHash, "FAILED");
            repository.audit(null, null, "LOGIN", "REJECTED", requestId);
            throw new ConsoleLoginRejectedException(false);
        }

        var rawToken = randomToken();
        var idleExpiry = now.plus(settings.idleDuration());
        var absoluteExpiry = now.plus(settings.absoluteDuration());
        var sessionId = repository.create(settings.username(), sha256(rawToken), now, idleExpiry, absoluteExpiry,
                settings.maxSessions());
        repository.recordLoginAttempt(principalHash, sourceHash, "SUCCEEDED");
        repository.audit(settings.username(), sessionId, "LOGIN", "SUCCEEDED", requestId);
        return new IssuedSession(sessionId, settings.username(), rawToken, idleExpiry, absoluteExpiry);
    }

    public Optional<ConsoleSessionRepository.SessionRecord> authenticate(String rawToken) {
        if (rawToken == null || rawToken.length() < 32 || rawToken.length() > 256) {
            return Optional.empty();
        }
        var now = clock.instant();
        var session = repository.findActive(sha256(rawToken), now);
        session.ifPresent(record -> repository.touch(record.id(), now, now.plus(settings.idleDuration())));
        return session;
    }

    public String issueCsrf(UUID sessionId) {
        var rawToken = hmacBase64("csrf:" + sessionId);
        repository.rotateCsrf(sessionId, sha256(rawToken));
        return rawToken;
    }

    public boolean csrfMatches(UUID sessionId, String rawCsrf) {
        return rawCsrf != null && rawCsrf.length() >= 32 && rawCsrf.length() <= 256
                && repository.csrfMatches(sessionId, sha256(rawCsrf));
    }

    public void logout(UUID sessionId, String principal, String requestId) {
        repository.revoke(sessionId, "LOGOUT");
        repository.audit(principal, sessionId, "LOGOUT", "REVOKED", requestId);
    }

    private String hmac(String value) {
        return HexFormat.of().formatHex(hmacBytes(value));
    }

    private String hmacBase64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes(value));
    }

    private byte[] hmacBytes(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.auditPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static String randomToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String bounded(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        var cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() <= maximumLength ? cleaned : cleaned.substring(0, maximumLength);
    }

    public record IssuedSession(UUID id, String principal, String rawToken, Instant idleExpiresAt,
            Instant absoluteExpiresAt) {
    }
}
