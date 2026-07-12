package com.dduo.faultlab;

import java.sql.SQLException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/faults")
@ConditionalOnExpression("'${faultlab.role}' == 'payment' and '${faultlab.controls-enabled}' == 'true'")
public class FaultControlController {

    private static final Logger log = LoggerFactory.getLogger(FaultControlController.class);

    private final FaultState faultState;
    private final FaultMetrics metrics;
    private final HikariExhaustionManager hikariExhaustionManager;
    private final ThreadPoolFaultManager threadPoolFaultManager;
    private final byte[] controlToken;

    public FaultControlController(FaultState faultState, FaultMetrics metrics, HikariExhaustionManager hikariExhaustionManager,
            ThreadPoolFaultManager threadPoolFaultManager, Environment environment) {
        this.faultState = faultState;
        this.metrics = metrics;
        this.hikariExhaustionManager = hikariExhaustionManager;
        this.threadPoolFaultManager = threadPoolFaultManager;
        this.controlToken = environment.getProperty("faultlab.control-token", "").getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{fault}/enable")
    public ResponseEntity<GroundTruth> enable(@PathVariable String fault,
            @RequestHeader(name = "X-Fault-Lab-Control-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        return change(fault, true);
    }

    @PostMapping("/{fault}/disable")
    public ResponseEntity<GroundTruth> disable(@PathVariable String fault,
            @RequestHeader(name = "X-Fault-Lab-Control-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        return change(fault, false);
    }

    @GetMapping("/ground-truth")
    public ResponseEntity<GroundTruth> groundTruth(
            @RequestHeader(name = "X-Fault-Lab-Control-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot());
    }

    private ResponseEntity<GroundTruth> change(String externalFault, boolean enabled) {
        var type = FaultType.fromExternalName(externalFault);
        if (type.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            if (type.get() == FaultType.HIKARI_EXHAUSTION) {
                if (enabled) {
                    hikariExhaustionManager.enable();
                } else {
                    hikariExhaustionManager.disable();
                }
            }
            if (type.get() == FaultType.THREAD_POOL_EXHAUSTION) {
                if (enabled) {
                    threadPoolFaultManager.enable();
                } else {
                    threadPoolFaultManager.disable();
                }
            }
            faultState.setEnabled(type.get(), enabled);
            metrics.setFaultActive(type.get(), enabled);
            metrics.recordInjection(type.get(), enabled);
            log.atWarn().addKeyValue("event", "faultlab_fault_state_changed").addKeyValue("fault", type.get().externalName())
                    .addKeyValue("enabled", enabled).log("Fault lab state changed");
            return ResponseEntity.ok(snapshot());
        } catch (SQLException exception) {
            log.atError().addKeyValue("event", "faultlab_fault_enable_failed").addKeyValue("fault", type.get().externalName())
                    .log("Could not acquire fixed fault-lab database connections", exception);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(snapshot());
        }
    }

    private GroundTruth snapshot() {
        var states = new LinkedHashMap<String, Boolean>();
        faultState.snapshot().forEach((type, enabled) -> states.put(type.externalName(), enabled));
        return new GroundTruth(states, hikariExhaustionManager.holderCount(), Instant.now(), faultState.lastChangedAt());
    }

    private boolean authorized(String token) {
        return controlToken.length > 0 && token != null
                && MessageDigest.isEqual(controlToken, token.getBytes(StandardCharsets.UTF_8));
    }

    public record GroundTruth(Map<String, Boolean> faults, int heldHikariConnections, Instant observedAt, Instant lastChangedAt) {
    }
}
