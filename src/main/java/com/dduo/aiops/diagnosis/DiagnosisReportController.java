package com.dduo.aiops.diagnosis;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the latest immutable report without exposing raw telemetry payloads. */
@RestController
@RequestMapping("/api/v1/incidents")
public class DiagnosisReportController {

    private final JdbcTemplate jdbcTemplate;

    public DiagnosisReportController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping(value = "/{incidentId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> latest(@PathVariable UUID incidentId) {
        var reports = jdbcTemplate.query("""
                SELECT report_json::text
                FROM diagnosis_report
                WHERE incident_id = ?
                ORDER BY generated_at DESC
                LIMIT 1
                """, (resultSet, rowNumber) -> resultSet.getString(1), incidentId);
        if (reports.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Spring Boot 4 uses Jackson 3 for HTTP conversion while the stored JSON is
        // read with the application's mapper. Returning the validated JSON text avoids
        // serializing JsonNode implementation properties instead of the report body.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(reports.get(0));
    }
}
