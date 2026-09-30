package com.dduo.aiops.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

class CatalogHealthServiceSpringWiringTests {

    @Test
    void springCanConstructTheProductionCatalogHealthService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("catalog-wiring", Map.of(
                    "aidiag.stage3.prometheus-base-url", "http://127.0.0.1:1",
                    "aidiag.catalog.freshness-seconds", "60")));
            context.register(WiringConfiguration.class);
            context.refresh();
            var service = context.getBean(CatalogHealthService.class);
            assertThat(service).isNotNull();
            assertThat(service.current().queriedAt()).isEqualTo(Instant.parse("2026-09-10T06:00:00Z"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CatalogHealthService.class)
    static class WiringConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-10T06:00:00Z"), java.time.ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ServiceCatalogRegistry registry() {
            var registry = mock(ServiceCatalogRegistry.class);
            when(registry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, Instant.EPOCH, "digest", List.of()));
            return registry;
        }
    }
}
