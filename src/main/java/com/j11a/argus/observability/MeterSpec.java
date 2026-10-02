package com.j11a.argus.observability;

import java.util.List;
import java.util.Set;

public record MeterSpec(String name, MeterKind kind, String baseUnit, Set<String> tags) {

    private static final List<String> DISTRIBUTION_SUFFIXES = List.of("_bucket", "_count", "_sum", "_max");
    private static final String SECONDS = "seconds";

    public String prometheusBase() {
        String base = name.replace('.', '_');
        String unit = kind == MeterKind.TIMER ? SECONDS : baseUnit;
        return unit == null ? base : base + "_" + unit;
    }

    public Set<String> prometheusSeries() {
        String base = prometheusBase();
        return switch (kind) {
            case TIMER, DISTRIBUTION_SUMMARY -> DISTRIBUTION_SUFFIXES.stream()
                    .map(suffix -> base + suffix)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            case COUNTER -> Set.of(base + "_total");
            case GAUGE -> Set.of(base);
        };
    }
}
