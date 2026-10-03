package com.j11a.argus.ingest;

import com.j11a.argus.ingest.dedup.LinkFallback;
import java.util.Map;

public record PersistCounts(
        int inserted,
        Map<String, Integer> updated,
        int linked,
        int unchanged,
        Map<String, Integer> skipped,
        Map<LinkFallback, Integer> linkFallbacks) {

    public PersistCounts {
        updated = Map.copyOf(updated);
        skipped = Map.copyOf(skipped);
        linkFallbacks = Map.copyOf(linkFallbacks);
    }

    public int updatedTotal() {
        return updated.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int skippedTotal() {
        return skipped.values().stream().mapToInt(Integer::intValue).sum();
    }
}
