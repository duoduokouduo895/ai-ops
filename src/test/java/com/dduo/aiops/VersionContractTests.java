package com.dduo.aiops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class VersionContractTests {

    @Test
    void versionLedgerMatchesBuildAndRuntimeContracts() throws IOException {
        var versions = versions();
        var pom = Files.readString(Path.of("pom.xml"));
        var faultLabPom = Files.readString(Path.of("fault-lab/pom.xml"));
        var dockerfile = Files.readString(Path.of("Dockerfile"));
        var faultLabDockerfile = Files.readString(Path.of("fault-lab/Dockerfile"));

        assertThat(versions).containsEntry("JAVA_VERSION", "21.0.11_10")
                .containsEntry("MAVEN_VERSION", "3.9.16")
                .containsEntry("SPRING_BOOT_VERSION", "4.1.0")
                .containsEntry("SPRING_AI_VERSION", "2.0.0")
                .containsEntry("SPRINGDOC_VERSION", "3.0.3")
                .containsEntry("RESILIENCE4J_VERSION", "2.4.0")
                .containsEntry("OTEL_JAVA_AGENT_VERSION", "2.29.0");
        assertThat(pom).contains("<version>" + versions.get("SPRING_BOOT_VERSION") + "</version>",
                "<spring-ai.version>" + versions.get("SPRING_AI_VERSION") + "</spring-ai.version>",
                "<springdoc.version>" + versions.get("SPRINGDOC_VERSION") + "</springdoc.version>",
                "<resilience4j.version>" + versions.get("RESILIENCE4J_VERSION") + "</resilience4j.version>");
        assertThat(faultLabPom).contains("<version>" + versions.get("SPRING_BOOT_VERSION") + "</version>");
        for (var file : new String[] { dockerfile, faultLabDockerfile }) {
            assertThat(file).contains("maven:" + versions.get("MAVEN_VERSION") + "-eclipse-temurin-21",
                    "eclipse-temurin:" + versions.get("JAVA_VERSION") + "-jre",
                    "opentelemetry-javaagent:" + versions.get("OTEL_JAVA_AGENT_VERSION"));
        }
    }

    private static Map<String, String> versions() throws IOException {
        var values = new LinkedHashMap<String, String>();
        for (var line : Files.readAllLines(Path.of("versions.env"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var separator = line.indexOf('=');
            assertThat(separator).as("versions.env line must be KEY=VALUE: %s", line).isPositive();
            assertThat(values.put(line.substring(0, separator), line.substring(separator + 1)))
                    .as("duplicate version key: %s", line.substring(0, separator)).isNull();
        }
        return values;
    }
}
