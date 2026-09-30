package com.dduo.aiops.console.api;

import java.util.List;
import java.util.UUID;

import com.dduo.aiops.catalog.AidiagSelfMonitoring;
import com.dduo.aiops.catalog.CatalogHealthService;
import com.dduo.aiops.monitoring.ServiceSignalsService;
import com.dduo.aiops.monitoring.SignalRange;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/console")
@ConditionalOnProperty(prefix = "aidiag.console", name = "enabled", havingValue = "true")
public class ConsoleApiController {

    private static final String SERVICE = "[A-Za-z0-9._-]{1,128}";
    private static final String STATUS = "OPEN|DIAGNOSING|DIAGNOSED|FAILED|RESOLVED|CLOSED";
    private static final String SEVERITY = "INFO|WARNING|CRITICAL";

    private final ConsoleIncidentQueryService queryService;
    private final CatalogHealthService catalogHealthService;
    private final AidiagSelfMonitoring selfMonitoring;
    private final ServiceSignalsService signalsService;

    public ConsoleApiController(ConsoleIncidentQueryService queryService, CatalogHealthService catalogHealthService,
            AidiagSelfMonitoring selfMonitoring, ServiceSignalsService signalsService) {
        this.queryService = queryService;
        this.catalogHealthService = catalogHealthService;
        this.selfMonitoring = selfMonitoring;
        this.signalsService = signalsService;
    }

    @GetMapping("/overview")
    public ConsoleIncidentQueryService.Overview overview() {
        return queryService.overview();
    }

    @GetMapping("/services")
    public CatalogHealthService.CatalogView services() {
        return catalogHealthService.current();
    }

    @GetMapping("/overview/trend")
    public ConsoleIncidentQueryService.IncidentTrend trend(
            @RequestParam(defaultValue = "14") @Min(7) @Max(30) int days) {
        return queryService.trend(days);
    }

    @GetMapping("/services/{id}/signals")
    public ServiceSignalsService.SignalsView signals(@PathVariable @Pattern(regexp = SERVICE) String id,
            @RequestParam(defaultValue = "1h") @Pattern(regexp = SignalRange.PATTERN) String range) {
        return signalsService.signals(id, SignalRange.fromCode(range))
                .orElseThrow(() -> new ConsoleResourceNotFoundException("service signals"));
    }

    @GetMapping("/pipeline-health")
    public AidiagSelfMonitoring.PipelineHealthView pipelineHealth() {
        return selfMonitoring.current();
    }

    @GetMapping("/incidents")
    public ConsoleIncidentQueryService.IncidentPage incidents(
            @RequestParam(required = false) @Size(max = 256) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Pattern(regexp = SERVICE) String service,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = SEVERITY) String severity,
            @RequestParam(required = false) @Size(max = 100) String search) {
        return queryService.incidents(cursor, limit, service, status, severity, search);
    }

    @GetMapping("/incidents/{id}")
    public ConsoleIncidentQueryService.IncidentDetail incident(@PathVariable UUID id) {
        return queryService.detail(id);
    }

    @GetMapping("/incidents/{id}/report")
    public ConsoleIncidentQueryService.ReportView report(@PathVariable UUID id) {
        return queryService.report(id);
    }

    @GetMapping("/incidents/{id}/evidence")
    public List<ConsoleIncidentQueryService.EvidenceView> evidence(@PathVariable UUID id) {
        queryService.detail(id);
        return queryService.evidence(id);
    }

    @GetMapping("/incidents/{id}/timeline")
    public List<ConsoleIncidentQueryService.TimelineEntry> timeline(@PathVariable UUID id) {
        return queryService.timeline(id);
    }

    @GetMapping("/incidents/{id}/ai-attempts")
    public List<ConsoleIncidentQueryService.AiAttemptView> aiAttempts(@PathVariable UUID id) {
        return queryService.aiAttempts(id);
    }
}
