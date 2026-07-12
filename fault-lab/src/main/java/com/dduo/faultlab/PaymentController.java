package com.dduo.faultlab;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payments")
@ConditionalOnProperty(prefix = "faultlab", name = "role", havingValue = "payment")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final FaultState faultState;
    private final JdbcTemplate jdbcTemplate;
    private final ThreadPoolFaultManager threadPoolFaultManager;
    private final long downstreamDelayMs;

    public PaymentController(FaultState faultState, JdbcTemplate jdbcTemplate, ThreadPoolFaultManager threadPoolFaultManager,
            org.springframework.core.env.Environment environment) {
        this.faultState = faultState;
        this.jdbcTemplate = jdbcTemplate;
        this.threadPoolFaultManager = threadPoolFaultManager;
        this.downstreamDelayMs = environment.getProperty("faultlab.downstream-delay-ms", Long.class, 3000L);
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@RequestHeader("X-Fault-Lab-Run-Id") String runId) {
        var span = Span.current();
        span.setAttribute("faultlab.run.id", runId);
        span.setAttribute("faultlab.role", "payment");

        if (faultState.isEnabled(FaultType.DOWNSTREAM_TIMEOUT)) {
            logFault(runId, FaultType.DOWNSTREAM_TIMEOUT, "faultlab_downstream_delay_started");
            try {
                Thread.sleep(downstreamDelayMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return unavailable(runId, "payment delay interrupted");
            }
        }

        try {
            if (faultState.isEnabled(FaultType.DATABASE_SLOW_QUERY)) {
                logFault(runId, FaultType.DATABASE_SLOW_QUERY, "faultlab_database_slow_query_started");
                jdbcTemplate.execute("SELECT pg_sleep(2)");
            }
            threadPoolFaultManager.submitPaymentWork();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.atInfo().addKeyValue("event", "faultlab_payment_succeeded").addKeyValue("runId", runId)
                    .log("Fault lab payment completed");
            return ResponseEntity.ok(new PaymentResponse(runId, "PAID", Instant.now()));
        } catch (CannotGetJdbcConnectionException exception) {
            logFault(runId, FaultType.HIKARI_EXHAUSTION, "faultlab_hikari_connection_timeout");
            return unavailable(runId, "database connection unavailable");
        } catch (RejectedExecutionException exception) {
            logFault(runId, FaultType.THREAD_POOL_EXHAUSTION, "faultlab_thread_pool_rejected");
            return unavailable(runId, "payment worker pool saturated");
        }
    }

    private ResponseEntity<PaymentResponse> unavailable(String runId, String status) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new PaymentResponse(runId, status, Instant.now()));
    }

    private void logFault(String runId, FaultType type, String event) {
        Span.current().setAttribute("faultlab.active.fault", type.externalName());
        log.atWarn().addKeyValue("event", event).addKeyValue("fault", type.externalName()).addKeyValue("runId", runId)
                .log("Fault lab injected controlled fault");
    }

    public record PaymentResponse(String runId, String status, Instant processedAt) {
    }
}

