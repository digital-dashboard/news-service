package com.j11a.argus.ingest.dedup;

import java.util.List;
import java.util.Map;

public record Resolution(List<EntryDecision> decisions, Map<LinkFallback, Integer> linkFallbacks) {
    public Resolution {
        decisions = List.copyOf(decisions);
        linkFallbacks = Map.copyOf(linkFallbacks);
    }
}
