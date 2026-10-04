package com.j11a.argus.feed.api;

import com.j11a.argus.source.FeedSourceChangedException;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;

/**
 * A feed that moves to another source while a request holds the old source's lock makes that transaction roll back.
 * The work is tried once more with the feed's new source; if it moved again, the caller gets a 409 and can retry.
 */
final class SourceChangeRetry {

    private SourceChangeRetry() {
    }

    static void twice(long feedId, String doing, Runnable work) {
        try {
            work.run();
        } catch (FeedSourceChangedException first) {
            try {
                work.run();
            } catch (FeedSourceChangedException second) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Feed " + feedId + " moved to another source while it was being " + doing + "; retry.");
            }
        }
    }
}
