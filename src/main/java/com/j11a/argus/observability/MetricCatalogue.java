package com.j11a.argus.observability;

import static com.j11a.argus.observability.MetricNames.Tags.DECISION;
import static com.j11a.argus.observability.MetricNames.Tags.FEED_ID;
import static com.j11a.argus.observability.MetricNames.Tags.KIND;
import static com.j11a.argus.observability.MetricNames.Tags.OUTCOME;
import static com.j11a.argus.observability.MetricNames.Tags.REASON;
import static com.j11a.argus.observability.MetricNames.Tags.SCHEDULED_JOB;
import static com.j11a.argus.observability.MetricNames.Tags.SOURCE;
import static com.j11a.argus.observability.MetricNames.Tags.STATE;
import static com.j11a.argus.observability.MetricNames.Tags.TRIGGER;
import static com.j11a.argus.observability.MetricNames.Tags.TYPE;

import java.util.List;
import java.util.Set;

public final class MetricCatalogue {

    // Observation-backed timers also get an automatic error tag and a .active series; those are not catalogued.
    private static final List<MeterSpec> ALL = List.of(
            new MeterSpec(MetricNames.FETCH, MeterKind.TIMER, null, Set.of(SOURCE, OUTCOME, REASON)),
            new MeterSpec(MetricNames.INGEST, MeterKind.TIMER, null, Set.of(SOURCE, OUTCOME)),
            new MeterSpec(MetricNames.FETCH_SIZE, MeterKind.DISTRIBUTION_SUMMARY, "bytes", Set.of(SOURCE)),
            new MeterSpec(MetricNames.INGEST_ENTRIES, MeterKind.COUNTER, null, Set.of(SOURCE, DECISION, REASON)),
            new MeterSpec(MetricNames.PARSE_MISSING, MeterKind.COUNTER, null, Set.of(SOURCE, KIND)),
            new MeterSpec(MetricNames.POLL, MeterKind.TIMER, null, Set.of(TRIGGER, OUTCOME)),
            new MeterSpec(MetricNames.FETCH_RETRY, MeterKind.COUNTER, null, Set.of(SOURCE)),
            new MeterSpec(MetricNames.SCHEDULED_JOB, MeterKind.COUNTER, null, Set.of(SCHEDULED_JOB, OUTCOME)),
            new MeterSpec(MetricNames.FEED_STATE, MeterKind.GAUGE, null, Set.of(FEED_ID, STATE)),
            new MeterSpec(MetricNames.FEED_CONSECUTIVE_FAILURES, MeterKind.GAUGE, null, Set.of(FEED_ID)),
            new MeterSpec(MetricNames.FEED_SINCE_LAST_SUCCESS, MeterKind.GAUGE, "seconds", Set.of(FEED_ID)),
            new MeterSpec(MetricNames.POLL_LAST_SUCCESS, MeterKind.GAUGE, "seconds", Set.of()),
            new MeterSpec(MetricNames.INGEST_LINK_FALLBACK, MeterKind.COUNTER, null, Set.of(SOURCE, OUTCOME)),
            new MeterSpec(MetricNames.INGEST_LOCK_WAIT, MeterKind.TIMER, null, Set.of(SOURCE)),
            new MeterSpec(MetricNames.FEED_REDIRECT, MeterKind.COUNTER, null, Set.of(SOURCE, OUTCOME)),
            new MeterSpec(MetricNames.FEED_IDENTITY_CONFLICT, MeterKind.COUNTER, null, Set.of(KIND)),
            new MeterSpec(MetricNames.SOURCE_MERGE, MeterKind.TIMER, null, Set.of(TYPE, OUTCOME)),
            new MeterSpec(MetricNames.ARTICLE_COLLAPSED, MeterKind.COUNTER, null, Set.of(TYPE)));

    private static final Set<String> ALLOWED_TAGS = MetricNames.Tags.all();

    private MetricCatalogue() {
    }

    public static List<MeterSpec> all() {
        return ALL;
    }

    public static Set<String> allowedTags() {
        return ALLOWED_TAGS;
    }
}
