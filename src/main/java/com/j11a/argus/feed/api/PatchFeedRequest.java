package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Topic;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Every field is optional, but at least one must be given. */
public record PatchFeedRequest(
        @Nullable Boolean enabled,
        @Size(min = 1, max = FeedService.MAX_NAME_LENGTH) @Nullable String name,
        @Nullable Topic topic,
        @Positive @Nullable Long sourceId,
        @Size(max = 2048) @AbsoluteHttpUrl @Nullable String url) {

    /** Enables or disables the feed and changes nothing else. */
    public PatchFeedRequest(boolean enabled) {
        this(enabled, null, null, null, null);
    }

    public boolean isEmpty() {
        return enabled == null && name == null && topic == null && sourceId == null && url == null;
    }
}
