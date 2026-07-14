package com.dduo.aiops.aid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails closed until a dedicated Stage 4 operator password is mounted. */
@Component
public class Stage4OperatorAuthenticator {

    private final byte[] expectedUsername;
    private final byte[] expectedPassword;

    @Autowired
    public Stage4OperatorAuthenticator(Environment environment) {
        this(environment.getProperty("aidiag.stage4.operator.username", "aidiag-operator"),
                environment.getProperty("aidiag.stage4.operator.password", ""));
    }

    Stage4OperatorAuthenticator(String username, String password) {
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
            var usernameMatches = MessageDigest.isEqual(expectedUsername,
                    decoded.substring(0, separator).getBytes(StandardCharsets.UTF_8));
            var passwordMatches = MessageDigest.isEqual(expectedPassword,
                    decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8));
            return usernameMatches & passwordMatches;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
