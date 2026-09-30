package com.dduo.aiops.console.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.dduo.aiops.catalog.AidiagSelfMonitoring;
import com.dduo.aiops.catalog.CatalogHealthService;
import com.dduo.aiops.monitoring.ServiceSignalsService;
import org.junit.jupiter.api.Test;

class ConsoleApiControllerTests {

    @Test
    void pipelineHealthReturnsTheCachedSnapshotWithoutRefreshingDiagnosis() {
        var monitoring = mock(AidiagSelfMonitoring.class);
        var view = AidiagSelfMonitoring.PipelineHealthView.notSampled(Instant.parse("2026-09-10T06:00:00Z"));
        when(monitoring.current()).thenReturn(view);
        var controller = new ConsoleApiController(mock(ConsoleIncidentQueryService.class),
                mock(CatalogHealthService.class), monitoring, mock(ServiceSignalsService.class));
        assertThat(controller.pipelineHealth()).isSameAs(view);
    }
}
