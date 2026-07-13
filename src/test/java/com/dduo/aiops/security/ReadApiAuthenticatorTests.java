package com.dduo.aiops.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class ReadApiAuthenticatorTests {

    @Test
    void acceptsOnlyTheConfiguredCredentials() {
        var authenticator = new ReadApiAuthenticator("reader", "a-long-read-only-password");

        assertThat(authenticator.isAuthorized(basic("reader", "a-long-read-only-password"))).isTrue();
        assertThat(authenticator.isAuthorized(basic("reader", "wrong"))).isFalse();
        assertThat(authenticator.isAuthorized(basic("wrong", "a-long-read-only-password"))).isFalse();
    }

    @Test
    void failsClosedForMissingConfigurationAndMalformedHeaders() {
        var authenticator = new ReadApiAuthenticator("reader", "");

        assertThat(authenticator.isAuthorized(basic("reader", "anything"))).isFalse();
        assertThat(authenticator.isAuthorized(null)).isFalse();
        assertThat(authenticator.isAuthorized("Bearer token")).isFalse();
        assertThat(authenticator.isAuthorized("Basic not-base64")).isFalse();
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
