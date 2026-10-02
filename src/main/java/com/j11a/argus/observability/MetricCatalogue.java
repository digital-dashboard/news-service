package com.j11a.argus.observability;

import java.util.List;
import java.util.Set;

public final class MetricCatalogue {

    public static final List<MeterSpec> ALL = List.of();

    public static final Set<String> ALLOWED_TAGS = MetricNames.Tags.ALL;

    private MetricCatalogue() {
    }
}
