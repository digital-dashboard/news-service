package com.j11a.argus.ingest;

import org.jspecify.annotations.Nullable;

public record IngestReport(
        long feedId,
        Outcome outcome,
        @Nullable String failureReason,
        int entriesSeen,
        int inserted,
        int unchanged,
        int skipped) {

    public enum Outcome { COMPLETED, NOT_MODIFIED, FAILED }

    static IngestReport notModified(long feedId) {
        return new IngestReport(feedId, Outcome.NOT_MODIFIED, null, 0, 0, 0, 0);
    }

    static IngestReport failed(long feedId, String reason) {
        return new IngestReport(feedId, Outcome.FAILED, reason, 0, 0, 0, 0);
    }

    static IngestReport completed(long feedId, int entriesSeen, PersistCounts counts) {
        return new IngestReport(feedId, Outcome.COMPLETED, null, entriesSeen,
                counts.inserted(), counts.unchanged(), counts.skippedTotal());
    }
}
