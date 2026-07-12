package com.dduo.aiops.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class WebhookAuthenticatorTests {

    private final WebhookAuthenticator authenticator = new WebhookAuthenticator("alertmanager", "test-password");

    @Test
    void acceptsOnlyMatchingBasicCredentials() {
        assertThat(authenticator.isAuthorized(basic("alertmanager", "test-password"))).isTrue();
        assertThat(authenticator.isAuthorized(basic("alertmanager", "wrong"))).isFalse();
        assertThat(authenticator.isAuthorized(basic("other", "test-password"))).isFalse();
    }

    @Test
    void rejectsMissingAndMalformedHeaders() {
        assertThat(authenticator.isAuthorized(null)).isFalse();
        assertThat(authenticator.isAuthorized("Bearer value")).isFalse();
        assertThat(authenticator.isAuthorized("Basic not-base64")).isFalse();
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
