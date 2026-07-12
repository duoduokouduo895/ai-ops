package com.dduo.aiops.common.config;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
