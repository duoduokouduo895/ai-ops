package com.dduo.faultlab;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@ConditionalOnProperty(prefix = "faultlab", name = "role", havingValue = "order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final URI paymentUri;
    private final Duration timeout;

    public OrderController(Environment environment) {
        this.paymentUri = URI.create(environment.getRequiredProperty("faultlab.payment-url") + "/internal/payments");
        this.timeout = Duration.ofMillis(environment.getProperty("faultlab.order-request-timeout-ms", Long.class, 1000L));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder() {
        var runId = UUID.randomUUID().toString();
        var span = Span.current();
        span.setAttribute("faultlab.run.id", runId);
        span.setAttribute("faultlab.role", "order");
        span.addEvent("faultlab.order.created");

        var request = HttpRequest.newBuilder(paymentUri)
                .timeout(timeout)
                .header("X-Fault-Lab-Run-Id", runId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.atInfo().addKeyValue("event", "faultlab_order_completed").addKeyValue("runId", runId)
                        .addKeyValue("paymentStatus", response.statusCode()).log("Fault lab order completed");
                return ResponseEntity.status(HttpStatus.CREATED).body(new OrderResponse(runId, "COMPLETED", Instant.now()));
            }
            log.atWarn().addKeyValue("event", "faultlab_order_payment_unavailable").addKeyValue("runId", runId)
                    .addKeyValue("paymentStatus", response.statusCode()).log("Fault lab payment unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new OrderResponse(runId, "PAYMENT_UNAVAILABLE", Instant.now()));
        } catch (IOException exception) {
            log.atWarn().addKeyValue("event", "faultlab_order_downstream_timeout").addKeyValue("runId", runId)
                    .log("Fault lab order downstream request failed", exception);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(new OrderResponse(runId, "PAYMENT_TIMEOUT", Instant.now()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new OrderResponse(runId, "INTERRUPTED", Instant.now()));
        }
    }

    public record OrderResponse(String runId, String status, Instant createdAt) {
    }
}

