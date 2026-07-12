package com.dduo.aiops.aid;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class Stage4OperatorAuthenticatorTests {

    @Test
    void failsClosedWithoutAnOperatorPassword() {
        var authenticator = new Stage4OperatorAuthenticator("operator", "");

        assertThat(authenticator.isAuthorized(basic("operator", ""))).isFalse();
    }

    @Test
    void acceptsOnlyTheDedicatedOperatorCredentials() {
        var authenticator = new Stage4OperatorAuthenticator("operator", "test-password");

        assertThat(authenticator.isAuthorized(basic("operator", "test-password"))).isTrue();
        assertThat(authenticator.isAuthorized(basic("operator", "wrong"))).isFalse();
        assertThat(authenticator.isAuthorized("Bearer value")).isFalse();
    }

    @Test
    void springCanInstantiateTheAuthenticatorWhenTestConstructorAlsoExists() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Stage4OperatorAuthenticator.class);
            context.refresh();

            assertThat(context.getBean(Stage4OperatorAuthenticator.class)).isNotNull();
        }
    }

    @Test
    void applicationYamlDoesNotContainACircularOperatorPasswordPlaceholder() throws Exception {
        var environment = new StandardEnvironment();
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(source -> environment.getPropertySources().addFirst(source));

        assertThat(new Stage4OperatorAuthenticator(environment).isAuthorized(null)).isFalse();
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
