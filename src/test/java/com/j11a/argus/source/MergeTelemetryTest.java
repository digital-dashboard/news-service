package com.j11a.argus.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

class MergeTelemetryTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final MergeTelemetry telemetry;

    MergeTelemetryTest() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        telemetry = new MergeTelemetry(observations, meters);
    }

    private Counter collapsed(String type) {
        return meters.find(MetricNames.ARTICLE_COLLAPSED).tag("type", type).counter();
    }

    private Timer timer(String type, String outcome) {
        return meters.find(MetricNames.SOURCE_MERGE).tag("type", type).tag("outcome", outcome).timer();
    }

    @Test
    void aMergeIsTimedAsCompletedAndCountsItsCollapsedArticles() {
        telemetry.merge(1, 2, () -> new SourceMergeResponse(1, 2, 1, 5, 3, 3));

        assertThat(timer("merge", "completed").count()).isEqualTo(1);
        assertThat(collapsed("merge").count()).isEqualTo(3);
        assertThat(timer("merge", "completed").getId().getTags()).extracting(t -> t.getKey())
                .doesNotContain("sourceId", "source.id", "target.source.id");
    }

    @Test
    void aFeedMoveCountsUnderItsOwnTypeAndAZeroRunStillCreatesTheSeries() {
        telemetry.feedMove(7, 1, 2, () -> new MoveResult(7, 1, 2, 4, 0, 0, 0, false));

        assertThat(timer("feed_move", "completed").count()).isEqualTo(1);
        assertThat(collapsed("feed_move")).isNotNull();
        assertThat(collapsed("feed_move").count()).isZero();
        assertThat(collapsed("merge")).isNull();
    }

    @Test
    void aFailedRunIsTimedAsFailedCountsNothingAndRethrows() {
        assertThatThrownBy(() -> telemetry.merge(1, 2, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(timer("merge", "failed").count()).isEqualTo(1);
        assertThat(collapsed("merge")).isNull();
    }
}
