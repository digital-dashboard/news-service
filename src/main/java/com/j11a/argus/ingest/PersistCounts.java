package com.j11a.argus.ingest;

import java.util.Map;

/** skipped maps a reason tag to how many entries were skipped for it. */
public record PersistCounts(int inserted, int unchanged, Map<String, Integer> skipped) {

    public PersistCounts {
        skipped = Map.copyOf(skipped);
    }

    public int skippedTotal() {
        return skipped.values().stream().mapToInt(Integer::intValue).sum();
    }
}
