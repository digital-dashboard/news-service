package com.j11a.argus.observability;

import java.util.List;
import java.util.Set;

public final class MetricCatalogue {

    public static final List<MeterSpec> ALL = List.of();

    public static final Set<String> ALLOWED_TAGS = Set.of(
            MetricNames.Tags.SOURCE,
            MetricNames.Tags.FEED,
            MetricNames.Tags.FEED_ID,
            MetricNames.Tags.WATCH,
            MetricNames.Tags.TRIGGER,
            MetricNames.Tags.OUTCOME,
            MetricNames.Tags.REASON,
            MetricNames.Tags.DECISION,
            MetricNames.Tags.TYPE,
            MetricNames.Tags.SCHEDULED_JOB,
            MetricNames.Tags.KIND,
            MetricNames.Tags.STATE);

    private MetricCatalogue() {
    }
}
