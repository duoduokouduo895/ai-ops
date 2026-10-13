package com.dduo.aiops.console.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class ConsoleAccessTokenTests {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String AUD = "a".repeat(64);
    private static RSAKey key;

    @BeforeAll
    static void keys() throws Exception { key = new RSAKeyGenerator(2048).keyID("test-key").generate(); }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("aidiag.console.enabled", "true")
                .withProperty("aidiag.console.auth-mode", "access")
                .withProperty("aidiag.console.access.team-domain", "test-team.cloudflareaccess.com")
                .withProperty("aidiag.console.access.audience", AUD);
    }

    private NimbusJwtDecoder decoder() throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(ConsoleAccessTokenValidator.create(new ConsoleSecuritySettings(environment()),
                Clock.fixed(NOW, ZoneOffset.UTC)));
        return decoder;
    }

    private String token(Consumer<JWTClaimsSet.Builder> change, RSAKey signingKey) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://test-team.cloudflareaccess.com")
                .audience(AUD).subject("access-user-id").claim("email", "owner@example.com").claim("type", "app")
                .issueTime(Date.from(NOW.minusSeconds(60))).notBeforeTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(NOW.plusSeconds(3600)));
        change.accept(claims);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void acceptsASignedHumanApplicationTokenWithoutPasswordSecrets() throws Exception {
        assertThat(decoder().decode(token(claims -> { }, key)).getClaimAsString("email")).isEqualTo("owner@example.com");
        assertThat(new ConsoleSecuritySettings(environment()).passwordHash()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "iss", "aud", "exp", "iat", "nbf", "email", "sub", "type" })
    void rejectsMissingRequiredClaims(String claim) throws Exception {
        var jwt = token(claims -> claims.claim(claim, null), key);
        assertThatThrownBy(() -> decoder().decode(jwt)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredFutureWrongAudienceAndGlobalTokens() throws Exception {
        var changes = Map.<String, Consumer<JWTClaimsSet.Builder>>of(
                "expired", claims -> claims.expirationTime(Date.from(NOW.minusSeconds(60))),
                "future", claims -> claims.notBeforeTime(Date.from(NOW.plusSeconds(60))),
                "future-issued", claims -> claims.issueTime(Date.from(NOW.plusSeconds(60))),
                "audience", claims -> claims.audience("other-application"),
                "issuer", claims -> claims.issuer("https://evil.cloudflareaccess.com"),
                "global", claims -> claims.claim("type", "org"),
                "service-token", claims -> claims.claim("email", ""));
        for (var change : changes.values()) {
            var jwt = token(change, key);
            assertThatThrownBy(() -> decoder().decode(jwt)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsAnotherSigningKeyAndUnsignedTokens() throws Exception {
        var other = new RSAKeyGenerator(2048).keyID("other-key").generate();
        var jwt = token(claims -> { }, other);
        assertThatThrownBy(() -> decoder().decode(jwt)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder().decode("eyJhbGciOiJub25lIn0.eyJlbWFpbCI6Im93bmVyQGV4YW1wbGUuY29tIn0."))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void cachesJwksAndRefreshesWhenCloudflareRotatesTheSigningKey() throws Exception {
        var rotated = new RSAKeyGenerator(2048).keyID("rotated-key").generate();
        var keys = new AtomicReference<>(new JWKSet(key.toPublicJWK()).toString());
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            requests.incrementAndGet();
            var body = keys.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var decoder = NimbusJwtDecoder.withJwkSetUri("http://127.0.0.1:" + server.getAddress().getPort() + "/keys").build();
            decoder.setJwtValidator(ConsoleAccessTokenValidator.create(new ConsoleSecuritySettings(environment()),
                    Clock.fixed(NOW, ZoneOffset.UTC)));
            var original = token(claims -> { }, key);
            decoder.decode(original);
            var firstRequests = requests.get();
            decoder.decode(original);
            assertThat(requests.get()).isEqualTo(firstRequests);
            keys.set(new JWKSet(java.util.List.of(key.toPublicJWK(), rotated.toPublicJWK())).toString());
            assertThat(decoder.decode(token(claims -> { }, rotated)).getSubject()).isEqualTo("access-user-id");
            assertThat(requests.get()).isGreaterThan(firstRequests);
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://test-team.cloudflareaccess.com", "test-team.cloudflareaccess.com/extra",
            "evil.example.com", "replace-before-deploy.cloudflareaccess.com", "" })
    void rejectsUnsafeOrMissingTeamDomains(String domain) {
        assertThatThrownBy(() -> new ConsoleSecuritySettings(environment()
                .withProperty("aidiag.console.access.team-domain", domain))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsClosedOnMissingAudienceOrUnknownMode() {
        assertThatThrownBy(() -> new ConsoleSecuritySettings(environment().withProperty("aidiag.console.access.audience", "")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ConsoleSecuritySettings(environment().withProperty("aidiag.console.auth-mode", "oops")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
