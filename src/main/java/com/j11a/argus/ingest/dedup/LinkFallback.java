package com.j11a.argus.ingest.dedup;

public enum LinkFallback {
    GUID_REPLACED("guid_replaced"),
    GUARDED_HOMEPAGE("guarded_homepage"),
    GUARDED_SHARED("guarded_shared");

    private final String tag;

    LinkFallback(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
