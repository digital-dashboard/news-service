package com.j11a.argus.ingest;

import org.jspecify.annotations.Nullable;

public record IngestReport(
        long feedId,
        Outcome outcome,
        @Nullable String failureReason,
        int entriesSeen,
        int inserted,
        int updated,
        int linked,
        int unchanged,
        int skipped) {

    public IngestReport {
        if (entriesSeen != inserted + updated + linked + unchanged + skipped) {
            throw new IllegalArgumentException(
                    "entriesSeen (%d) must equal inserted (%d) + updated (%d) + linked (%d) + unchanged (%d) + skipped (%d)"
                            .formatted(entriesSeen, inserted, updated, linked, unchanged, skipped));
        }
    }

    public enum Outcome { COMPLETED, NOT_MODIFIED, FAILED }

    public static IngestReport notModified(long feedId) {
        return new IngestReport(feedId, Outcome.NOT_MODIFIED, null, 0, 0, 0, 0, 0, 0);
    }

    public static IngestReport failed(long feedId, String reason) {
        return new IngestReport(feedId, Outcome.FAILED, reason, 0, 0, 0, 0, 0, 0);
    }

    public static IngestReport completed(long feedId, int entriesSeen, PersistCounts counts) {
        return new IngestReport(feedId, Outcome.COMPLETED, null, entriesSeen,
                counts.inserted(), counts.updatedTotal(), counts.linked(), counts.unchanged(), counts.skippedTotal());
    }
}
