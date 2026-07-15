package com.dduo.aiops.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

@Component
public class ServiceCatalogCompiler {

    private static final Set<String> PROFILES = Set.of("ai-chat-v1", "aidiag-v1");
    private static final Set<String> DEFAULT_ALLOWED_HOSTS = Set.of("ai-chat-production", "aidiag-backend", "postgres",
            "prometheus", "loki", "tempo", "alertmanager", "grafana", "mysql", "redis", "rabbitmq",
            "elasticsearch-v9-candidate", "nacos-v3-candidate", "api.dduo.me");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ObjectMapper jsonMapper;
    private final Schema schema;
    private final Clock clock;

    public ServiceCatalogCompiler(ObjectMapper jsonMapper, Clock clock) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        try (InputStream input = requiredResource("/service-catalog/monitored-service.schema.json")) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load service catalog schema", exception);
        }
    }

    public ServiceCatalog.Snapshot compile(byte[] source) {
        if (source == null || source.length == 0 || source.length > 512_000) {
            throw new IllegalArgumentException("Service catalog source size is outside the reviewed bounds");
        }
        try {
            JsonNode tree = yamlMapper.readTree(source);
            var errors = schema.validate(tree);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Service catalog schema validation failed: " + errors.getFirst());
            }
            var catalog = yamlMapper.treeToValue(tree, ServiceCatalog.class);
            validateSemantics(catalog);
            var canonical = jsonMapper.writeValueAsBytes(tree);
            return new ServiceCatalog.Snapshot(catalog.version(), clock.instant(), sha256(canonical),
                    java.util.List.copyOf(catalog.services()));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Service catalog is not valid bounded YAML", exception);
        }
    }

    private void validateSemantics(ServiceCatalog catalog) {
        var serviceIds = new HashSet<String>();
        for (var service : catalog.services()) {
            if (!serviceIds.add(service.id())) {
                throw new IllegalArgumentException("Duplicate service id: " + service.id());
            }
            if (!PROFILES.contains(service.diagnosisProfile())) {
                throw new IllegalArgumentException("Unknown diagnosis profile: " + service.diagnosisProfile());
            }
            validateUrl(service.healthUrl(), "healthUrl");
            validateUrl(service.metricsUrl(), "metricsUrl");
            if (service.grafanaUrl() != null) {
                validateUrl(service.grafanaUrl(), "grafanaUrl");
            }
            var dependencyIds = new HashSet<String>();
            for (var dependency : service.dependencies()) {
                if (!dependencyIds.add(dependency.id())) {
                    throw new IllegalArgumentException("Duplicate dependency id in " + service.id() + ": "
                            + dependency.id());
                }
                if ("http".equals(dependency.kind())) {
                    validateUrl(dependency.target(), "dependency.target");
                } else {
                    validateTcpTarget(dependency.target());
                }
            }
        }
    }

    private static void validateUrl(String value, String field) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " is not a valid URI", exception);
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        var reviewedHost = DEFAULT_ALLOWED_HOSTS.contains(host)
                || ("grafanaUrl".equals(field) && "127.0.0.1".equals(host) && uri.getPort() == 13000);
        if (!("http".equals(scheme) || "https".equals(scheme)) || !reviewedHost
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException(field + " contains a non-reviewed address");
        }
    }

    private static void validateTcpTarget(String value) {
        var separator = value.lastIndexOf(':');
        if (separator < 1 || separator == value.length() - 1) {
            throw new IllegalArgumentException("TCP target must be host:port");
        }
        var host = value.substring(0, separator).toLowerCase(Locale.ROOT);
        int port;
        try {
            port = Integer.parseInt(value.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("TCP target port must be numeric", exception);
        }
        if (!DEFAULT_ALLOWED_HOSTS.contains(host) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("TCP target contains a non-reviewed address");
        }
    }

    static InputStream requiredResource(String path) {
        var input = ServiceCatalogCompiler.class.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException("Missing classpath resource: " + path);
        }
        return input;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
