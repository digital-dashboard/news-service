package com.j11a.argus.ingest.dedup;

public enum UpdateReason {
    CONTENT_CHANGED("content_changed"),
    TIMESTAMP_ONLY("timestamp_only"),
    INSERT_CONFLICT("insert_conflict");

    private final String tag;

    UpdateReason(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
