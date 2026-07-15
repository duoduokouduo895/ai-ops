package com.dduo.aiops.console.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = "aidiag.console.enabled=false")
@Import({ConsoleSecurityConfiguration.class, ConsoleDisabledSecurityBoundaryTests.SecurityTestConfiguration.class})
class ConsoleDisabledSecurityBoundaryTests {

    @jakarta.annotation.Resource
    MockMvc mockMvc;

    @Test
    void doesNotLetSpringSecurityTakeOverRequestsWhenConsoleIsDisabled() throws Exception {
        mockMvc.perform(get("/existing-controller-owned-path"))
                .andExpect(status().isNotFound());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class SecurityTestConfiguration {
    }
}
