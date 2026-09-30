package com.dduo.aiops.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TelemetryEvidenceEnvelopeTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mergesSeriesIntoAnUpperEnvelopeAndDropsInvalidSamples() throws Exception {
        var results = objectMapper.readTree("""
                [{"metric":{"instance":"a"},"values":[[100,"1"],[130,"5"],[160,"NaN"]]},
                 {"metric":{"instance":"b"},"values":[[100,"3"],[130,"2"],[160,"bad"]]}]
                """);

        var points = TelemetryEvidenceCollector.envelope(results);

        assertThat(points).hasSize(2);
        assertThat(points.get(0)).containsExactly(100, 3);
        assertThat(points.get(1)).containsExactly(130, 5);
    }

    @Test
    void downsamplesLongRangesWithoutLosingThePeak() throws Exception {
        var values = new StringBuilder();
        for (var index = 0; index < 500; index++) {
            values.append(index == 0 ? "" : ",").append("[").append(index * 30).append(",\"")
                    .append(index == 333 ? 99 : 1).append("\"]");
        }
        var results = objectMapper.readTree("[{\"metric\":{},\"values\":[" + values + "]}]");

        var points = TelemetryEvidenceCollector.envelope(results);

        assertThat(points).hasSizeLessThanOrEqualTo(TelemetryEvidenceCollector.MAX_SERIES_POINTS);
        assertThat(points.stream().mapToDouble(point -> point[1]).max().orElseThrow()).isEqualTo(99);
    }
}
