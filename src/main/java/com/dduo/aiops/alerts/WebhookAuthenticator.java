package com.dduo.aiops.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Verifies the private Alertmanager-to-backend Basic Auth boundary. */
@Component
public class WebhookAuthenticator {

    private final byte[] expectedUsername;
    private final byte[] expectedPassword;

    @Autowired
    public WebhookAuthenticator(Environment environment) {
        this(environment.getProperty("aidiag.alerts.webhook.username", "alertmanager"),
                environment.getProperty("aidiag.alerts.webhook.password", ""));
    }

    WebhookAuthenticator(String username, String password) {
        this.expectedUsername = username.getBytes(StandardCharsets.UTF_8);
        this.expectedPassword = password.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isAuthorized(String authorizationHeader) {
        if (expectedPassword.length == 0 || authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
            return false;
        }
        try {
            var decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6)), StandardCharsets.UTF_8);
            var separator = decoded.indexOf(':');
            if (separator < 0) {
                return false;
            }
            var username = decoded.substring(0, separator).getBytes(StandardCharsets.UTF_8);
            var password = decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedUsername, username) && MessageDigest.isEqual(expectedPassword, password);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
