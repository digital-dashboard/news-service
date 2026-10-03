package com.j11a.argus.ingest.dedup;

public enum SkipReason {
    MISSING_IDENTITY("missing_identity"),
    BATCH_DUPLICATE("batch_duplicate");

    private final String tag;

    SkipReason(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
