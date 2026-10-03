package com.j11a.argus.feed.poll;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PollTrigger {
    SCHEDULED("scheduled"),
    MANUAL("manual");

    private final String tag;

    PollTrigger(String tag) {
        this.tag = tag;
    }

    @JsonValue
    public String tag() {
        return tag;
    }
}
