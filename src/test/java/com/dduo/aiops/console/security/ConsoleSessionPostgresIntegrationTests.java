package com.dduo.aiops.console.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ConsoleSessionPostgresIntegrationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.5-pg18")
            .withDatabaseName("aidiag").withUsername("aidiag").withPassword("integration-only");

    static JdbcTemplate jdbcTemplate;
    static ConsoleSecuritySettings settings;
    static BCryptPasswordEncoder encoder;

    @BeforeAll
    static void initializeSchema() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        encoder = new BCryptPasswordEncoder(4);
        settings = new ConsoleSecuritySettings(new MockEnvironment()
                .withProperty("aidiag.console.enabled", "true")
                .withProperty("aidiag.console.username", "aidiag-admin")
                .withProperty("aidiag.console.password-hash", encoder.encode("integration-password"))
                .withProperty("aidiag.console.audit-pepper", "0123456789abcdef0123456789abcdef"));
    }

    @Test
    void restoresAnOpaqueSessionAfterServiceRestartAndEnforcesTheSessionCap() {
        var repository = new ConsoleSessionRepository(jdbcTemplate);
        var clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        var firstProcess = new ConsoleSessionService(repository, settings, encoder, clock);
        var issued = firstProcess.login("aidiag-admin", "integration-password", "127.0.0.1", "request-1");

        var restartedProcess = new ConsoleSessionService(repository, settings, encoder, clock);
        assertThat(restartedProcess.authenticate(issued.rawToken())).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM console_session WHERE id = ?", String.class,
                issued.id())).doesNotContain(issued.rawToken()).hasSize(64);

        for (int index = 0; index < 3; index++) {
            firstProcess.login("aidiag-admin", "integration-password", "127.0.0.1", "request-" + (index + 2));
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM console_session WHERE principal = 'aidiag-admin' AND revoked_at IS NULL",
                Integer.class)).isEqualTo(3);
    }
}
