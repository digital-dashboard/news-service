package com.j11a.argus.source;

import static com.j11a.argus.observability.MetricNames.Tags.OUTCOME;
import static com.j11a.argus.observability.MetricNames.Tags.TYPE;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.springframework.stereotype.Component;

/**
 * One Observation per merge or move gives the argus.source.merge timer and span. The type and outcome tags are low
 * cardinality; source and feed ids are span attributes only. The collapsed counter is incremented on every
 * successful run, by zero when nothing collapsed, so its series always exists.
 */
@Component
public class MergeTelemetry {

    static final String MERGE = "merge";
    static final String FEED_MOVE = "feed_move";
    private static final String COMPLETED = "completed";
    private static final String FAILED = "failed";

    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public MergeTelemetry(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    SourceMergeResponse merge(long sourceId, long targetSourceId, Supplier<SourceMergeResponse> work) {
        Observation observation = start(MERGE, sourceId, targetSourceId);
        return run(observation, MERGE, work, SourceMergeResponse::articlesCollapsed);
    }

    MoveResult feedMove(long feedId, long sourceId, long targetSourceId, Supplier<MoveResult> work) {
        Observation observation = start(FEED_MOVE, sourceId, targetSourceId)
                .highCardinalityKeyValue("feed.id", String.valueOf(feedId));
        return run(observation, FEED_MOVE, work, MoveResult::articlesCollapsed);
    }

    private Observation start(String type, long sourceId, long targetSourceId) {
        return Observation.createNotStarted(MetricNames.SOURCE_MERGE, observations)
                .lowCardinalityKeyValue(TYPE, type)
                .highCardinalityKeyValue("source.id", String.valueOf(sourceId))
                .highCardinalityKeyValue("target.source.id", String.valueOf(targetSourceId))
                .start();
    }

    private <T> T run(Observation observation, String type, Supplier<T> work, ToIntFunction<T> collapsed) {
        String outcome = FAILED;
        try (Observation.Scope ignored = observation.openScope()) {
            T result = work.get();
            outcome = COMPLETED;
            meters.counter(MetricNames.ARTICLE_COLLAPSED, TYPE, type).increment(collapsed.applyAsInt(result));
            return result;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.lowCardinalityKeyValue(OUTCOME, outcome);
            observation.stop();
        }
    }
}
