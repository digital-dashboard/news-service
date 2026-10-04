package com.j11a.argus.testsupport;

import com.j11a.argus.feed.api.PatchFeedRequest;

public final class PatchRequests {

    private PatchRequests() {
    }

    /** A PATCH that enables or disables the feed and changes nothing else. */
    public static PatchFeedRequest enabled(boolean enabled) {
        return new PatchFeedRequest(enabled, null, null, null, null);
    }
}
