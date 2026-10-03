package com.j11a.argus.ingest;

/** Reason codes stored in feed.last_error and reported in IngestReport. None can carry a URL or an upstream message. */
public final class FailureReasons {

    public static final String UNEXPECTED_ERROR = "unexpected_error";
    public static final String PERSIST_FAILED = "persist_failed";
    public static final String FIRST_INGEST_FAILED = "first_ingest_failed";
    public static final String FEED_DELETED = "feed_deleted";
    public static final String INTERRUPTED = "interrupted";
    public static final String DUPLICATE_FEED = "duplicate_feed";
    public static final String SOURCE_CHANGED = "source_changed";

    private FailureReasons() {
    }
}
