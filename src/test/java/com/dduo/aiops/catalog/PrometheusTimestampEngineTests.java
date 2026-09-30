package com.dduo.aiops.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Executes the fixed timestamp() expressions against Prometheus v3.13.1. A mock HTTP body cannot
 * prove that timestamp(label_replace(...)) collapses to evaluation time.
 */
class PrometheusTimestampEngineTests {

    private static final String VERSION = "3.13.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void timestampQueryAppliesToRawSelectors() {
        assertThat(CatalogHealthService.TIMESTAMP_QUERY)
                .contains("timestamp(up)", "timestamp(probe_success)", "timestamp(aidiag_database_reachable)")
                .doesNotContain("timestamp(label_replace")
                .doesNotContain("timestamp({__name__");
    }

    @Test
    @Timeout(180)
    void rawSelectorKeepsSampleTimeAtT0WhenEvaluatedAt120() throws Exception {
        var home = ensurePrometheusHome();
        assumeThat(home).as("prometheus v3.13.1 binary").isNotNull();
        var promtool = home.resolve(windows() ? "promtool.exe" : "promtool");
        var prometheus = home.resolve(windows() ? "prometheus.exe" : "prometheus");
        assumeThat(Files.isRegularFile(promtool) && Files.isRegularFile(prometheus)).isTrue();

        var work = Files.createTempDirectory("aidiag-promql-");
        var om = work.resolve("samples.om");
        Files.writeString(om, """
                # TYPE up gauge
                up{job="aidiag",service_name="aidiag"} 1 0
                # TYPE probe_success gauge
                probe_success{job="blackbox-ai-chat",service_name="ai-chat-production",component="application"} 1 0
                # EOF
                """, StandardCharsets.UTF_8);
        var tsdb = work.resolve("data");
        Files.createDirectories(tsdb);
        var create = new ProcessBuilder(promtool.toString(), "tsdb", "create-blocks-from", "openmetrics",
                om.toString(), tsdb.toString()).directory(work.toFile());
        create.redirectErrorStream(true);
        var created = create.start();
        var createOut = created.getInputStream().readAllBytes();
        assertThat(created.waitFor()).as(new String(createOut, StandardCharsets.UTF_8)).isZero();

        var config = work.resolve("prometheus.yml");
        Files.writeString(config, "global:\n  scrape_interval: 1h\nscrape_configs: []\n", StandardCharsets.UTF_8);
        var port = freePort();
        var server = new ProcessBuilder(prometheus.toString(), "--config.file=" + config, "--storage.tsdb.path=" + tsdb,
                "--web.listen-address=127.0.0.1:" + port, "--storage.tsdb.retention.time=10y",
                "--log.level=error").directory(work.toFile());
        server.redirectErrorStream(true);
        var process = server.start();
        try {
            waitUntilReady(port);
            var raw = instantQuery(port, "timestamp(up)", "120");
            var wrapped = instantQuery(port, "timestamp(label_replace(up,\"metric_name\",\"up\",\"\",\"\"))", "120");
            var fixed = instantQuery(port, CatalogHealthService.TIMESTAMP_QUERY, "120");
            assertThat(sampleValue(raw)).isEqualTo(0.0d);
            assertThat(sampleValue(wrapped)).isEqualTo(120.0d);
            var mapped = CatalogHealthService.TelemetrySnapshot.fromSamples(
                    java.time.Instant.ofEpochSecond(120),
                    MAPPER.readTree(instantQuery(port, CatalogHealthService.VALUE_QUERY, "120"))
                            .path("data").path("result"),
                    MAPPER.readTree(fixed).path("data").path("result"),
                    Duration.ofSeconds(60));
            assertThat(mapped.state("up", "aidiag", "aidiag", "")).isEqualTo(CatalogHealthService.HealthState.UNKNOWN);
            assertThat(mapped.observation("up", "aidiag", "aidiag", "").reason()).isEqualTo("STALE");
            assertThat(mapped.observation("up", "aidiag", "aidiag", "").sampledAt())
                    .isEqualTo(java.time.Instant.EPOCH);
        } finally {
            process.destroy();
            process.waitFor();
        }
    }

    private static Path ensurePrometheusHome() throws IOException, InterruptedException {
        var configured = System.getenv("PROMETHEUS_HOME");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        var cached = Path.of("target", "it", "prometheus-" + VERSION);
        var binary = cached.resolve(windows() ? "prometheus.exe" : "prometheus");
        if (Files.isRegularFile(binary)) {
            return cached;
        }
        var artifact = windows() ? "prometheus-" + VERSION + ".windows-amd64.zip"
                : "prometheus-" + VERSION + ".linux-amd64.tar.gz";
        var url = "https://github.com/prometheus/prometheus/releases/download/v" + VERSION + "/" + artifact;
        Files.createDirectories(cached.getParent());
        var archive = cached.getParent().resolve(artifact);
        var fetch = new ProcessBuilder("curl", "-fsSL", "-o", archive.toString(), url);
        fetch.redirectErrorStream(true);
        var process = fetch.start();
        var output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0 || !Files.isRegularFile(archive)) {
            return null;
        }
        Files.createDirectories(cached);
        if (artifact.endsWith(".zip")) {
            try (var zip = new ZipFile(archive.toFile())) {
                zip.stream().forEach(entry -> {
                    try {
                        var name = Path.of(entry.getName()).getFileName();
                        if (name != null && (name.toString().startsWith("prometheus") || name.toString().startsWith("promtool"))) {
                            try (var in = zip.getInputStream(entry)) {
                                Files.copy(in, cached.resolve(name.toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
        return Files.isRegularFile(binary) ? cached : null;
    }

    private static String instantQuery(int port, String query, String time) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/query?query="
                + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8) + "&time=" + time);
        var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static double sampleValue(String body) throws Exception {
        var value = MAPPER.readTree(body).path("data").path("result").path(0).path("value").path(1).asText();
        return Double.parseDouble(value);
    }

    private static void waitUntilReady(int port) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
        var deadline = System.currentTimeMillis() + 30_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/-/ready"))
                        .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception exception) {
                last = exception;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("prometheus did not become ready", last);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
