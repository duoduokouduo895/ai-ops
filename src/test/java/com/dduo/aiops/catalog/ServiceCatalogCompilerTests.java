package com.dduo.aiops.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceCatalogCompilerTests {

    private final ServiceCatalogCompiler compiler = new ServiceCatalogCompiler(new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void compilesTheReviewedCatalogAndIncludesAidiagSelfMonitoring() throws Exception {
        try (var input = ServiceCatalogCompiler.requiredResource("/service-catalog/catalog.yml")) {
            var result = compiler.compile(input.readAllBytes());
            assertThat(result.version()).isEqualTo(1);
            assertThat(result.services()).extracting(ServiceCatalog.MonitoredService::id)
                    .containsExactly("ai-chat", "aidiag");
            var aiChat = result.services().stream().filter(s -> "ai-chat".equals(s.id())).findFirst().orElseThrow();
            assertThat(aiChat.healthUrl()).isEqualTo("http://ai-chat-production:9091/actuator/health");
            assertThat(aiChat.metricsUrl()).isEqualTo("http://ai-chat-production:9091/actuator/prometheus");
            assertThat(aiChat.dependencies()).anySatisfy(dep -> {
                assertThat(dep.id()).isEqualTo("elasticsearch");
                assertThat(dep.target()).isEqualTo("elasticsearch-v9:9200");
            });
            assertThat(result.sourceDigest()).hasSize(64);
        }
    }

    @Test
    void rejectsDuplicateServiceIds() {
        assertThatThrownBy(() -> compiler.compile(catalog(
                service("ai-chat", "http://ai-chat-production:8080/actuator/health")
                        + service("ai-chat", "http://ai-chat-production:8080/actuator/health")).getBytes()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate service");
    }

    @Test
    void rejectsUnreviewedAddressesBeforeActivation() {
        assertThatThrownBy(() -> compiler.compile(catalog(service("unsafe",
                "http://169.254.169.254/latest/meta-data")).getBytes()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-reviewed address");
    }

    @Test
    void rejectsUnknownProfilesAtTheSchemaBoundary() {
        var source = catalog(service("unknown", "http://aidiag-backend:8080/actuator/health")
                .replace("aidiag-v1", "shell-exec"));
        assertThatThrownBy(() -> compiler.compile(source.getBytes())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema validation failed");
    }

    @Test
    void retainsLastKnownGoodSnapshotWhenExternalCandidateFails(@TempDir Path directory) throws Exception {
        var external = directory.resolve("catalog.yml");
        Files.writeString(external, catalog(service("aidiag", "http://aidiag-backend:8080/actuator/health")));
        var registry = new ServiceCatalogRegistry(compiler, external.toString());
        registry.initialize();
        var accepted = registry.snapshot();

        Files.writeString(external, catalog(service("unsafe", "http://169.254.169.254/latest/meta-data")));
        Files.setLastModifiedTime(external, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        registry.reloadExternalCandidate();

        assertThat(registry.snapshot()).isSameAs(accepted);
        assertThat(registry.snapshot().services()).extracting(ServiceCatalog.MonitoredService::id)
                .containsExactly("aidiag");
    }

    private static String catalog(String services) {
        return "version: 1\nservices:\n" + services;
    }

    private static String service(String id, String health) {
        return """
                  - id: %s
                    displayName: Test
                    environment: production
                    healthUrl: %s
                    metricsUrl: http://aidiag-backend:8080/actuator/prometheus
                    otelServiceName: aidiag
                    diagnosisProfile: aidiag-v1
                    dependencies: []
                """.formatted(id, health).stripTrailing() + "\n";
    }
}
