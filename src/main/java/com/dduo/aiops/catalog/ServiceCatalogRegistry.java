package com.dduo.aiops.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Compiles a candidate fully before atomically replacing the last-known-good catalog. */
@Component
public class ServiceCatalogRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCatalogRegistry.class);

    private final ServiceCatalogCompiler compiler;
    private final Path externalPath;
    private final AtomicReference<ServiceCatalog.Snapshot> current = new AtomicReference<>();
    private volatile long externalLastModified = Long.MIN_VALUE;

    public ServiceCatalogRegistry(ServiceCatalogCompiler compiler,
            @Value("${aidiag.catalog.external-path:}") String externalPath) {
        this.compiler = compiler;
        this.externalPath = externalPath == null || externalPath.isBlank() ? null : Path.of(externalPath).normalize();
    }

    @PostConstruct
    public void initialize() throws IOException {
        current.set(compiler.compile(readSource()));
        if (externalPath != null) {
            externalLastModified = Files.getLastModifiedTime(externalPath).toMillis();
        }
    }

    public ServiceCatalog.Snapshot snapshot() {
        return current.get();
    }

    @Scheduled(initialDelayString = "${aidiag.catalog.reload-initial-delay-ms:30000}",
            fixedDelayString = "${aidiag.catalog.reload-delay-ms:30000}")
    public void reloadExternalCandidate() {
        if (externalPath == null) {
            return;
        }
        try {
            var modified = Files.getLastModifiedTime(externalPath).toMillis();
            if (modified == externalLastModified) {
                return;
            }
            var candidate = compiler.compile(Files.readAllBytes(externalPath));
            current.set(candidate);
            externalLastModified = modified;
            LOGGER.atInfo().addKeyValue("event", "service_catalog_reload")
                    .addKeyValue("result", "activated").addKeyValue("digest", candidate.sourceDigest())
                    .log("Service catalog candidate activated");
        } catch (Exception exception) {
            LOGGER.atWarn().addKeyValue("event", "service_catalog_reload")
                    .addKeyValue("result", "rejected").addKeyValue("errorType", exception.getClass().getSimpleName())
                    .log("Service catalog candidate rejected; last-known-good snapshot retained");
        }
    }

    private byte[] readSource() throws IOException {
        if (externalPath != null) {
            return Files.readAllBytes(externalPath);
        }
        try (var input = ServiceCatalogCompiler.requiredResource("/service-catalog/catalog.yml")) {
            return input.readAllBytes();
        }
    }
}
