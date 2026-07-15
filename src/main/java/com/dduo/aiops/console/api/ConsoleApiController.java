package com.dduo.aiops.console.api;

import java.util.List;
import java.util.UUID;

import com.dduo.aiops.catalog.CatalogHealthService;
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

    public ConsoleApiController(ConsoleIncidentQueryService queryService, CatalogHealthService catalogHealthService) {
        this.queryService = queryService;
        this.catalogHealthService = catalogHealthService;
    }

    @GetMapping("/overview")
    public ConsoleIncidentQueryService.Overview overview() {
        return queryService.overview();
    }

    @GetMapping("/services")
    public CatalogHealthService.CatalogView services() {
        return catalogHealthService.current();
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
