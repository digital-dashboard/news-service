package com.j11a.argus.observability;

import static com.j11a.argus.observability.MetricNames.Tags.DECISION;
import static com.j11a.argus.observability.MetricNames.Tags.KIND;
import static com.j11a.argus.observability.MetricNames.Tags.OUTCOME;
import static com.j11a.argus.observability.MetricNames.Tags.REASON;
import static com.j11a.argus.observability.MetricNames.Tags.SOURCE;

import java.util.List;
import java.util.Set;

public final class MetricCatalogue {

    // Observation-backed timers also get an automatic error tag and a .active series; those are not catalogued.
    private static final List<MeterSpec> ALL = List.of(
            new MeterSpec(MetricNames.FETCH, MeterKind.TIMER, null, Set.of(SOURCE, OUTCOME, REASON)),
            new MeterSpec(MetricNames.INGEST, MeterKind.TIMER, null, Set.of(SOURCE, OUTCOME)),
            new MeterSpec(MetricNames.FETCH_SIZE, MeterKind.DISTRIBUTION_SUMMARY, "bytes", Set.of(SOURCE)),
            new MeterSpec(MetricNames.INGEST_ENTRIES, MeterKind.COUNTER, null, Set.of(SOURCE, DECISION, REASON)),
            new MeterSpec(MetricNames.PARSE_MISSING, MeterKind.COUNTER, null, Set.of(SOURCE, KIND)));

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
