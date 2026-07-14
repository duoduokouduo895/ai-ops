package com.dduo.aiops.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DiagnosisRunRepositoryTests {

    @Test
    void finalFailedAttemptMovesAnActiveIncidentToFailed() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        var repository = new DiagnosisRunRepository(jdbcTemplate);
        var run = run(3);

        assertThat(repository.scheduleRetry(run, 3, 5, "TEST_FAILURE", "failure")).isTrue();

        verify(jdbcTemplate).update(contains("UPDATE incident"), any(Object[].class));
    }

    @Test
    void staleFailureCannotChangeTheIncidentState() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(contains("SET status = 'FAILED'"), any(Object[].class))).thenReturn(0);
        var repository = new DiagnosisRunRepository(jdbcTemplate);

        assertThat(repository.scheduleRetry(run(3), 3, 5, "TEST_FAILURE", "failure")).isFalse();

        verify(jdbcTemplate, never()).update(contains("UPDATE incident"), any(Object[].class));
    }

    private static DiagnosisRunRepository.ClaimedRun run(int attempt) {
        return new DiagnosisRunRepository.ClaimedRun(UUID.randomUUID(), UUID.randomUUID(), attempt, "test-owner",
                UUID.randomUUID());
    }
}
