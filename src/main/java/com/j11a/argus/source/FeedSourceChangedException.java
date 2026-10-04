package com.j11a.argus.source;

/**
 * The feed moved to another source between the caller's read and its lock. The transaction holds the wrong source
 * lock, so it rolls back and the caller retries with the feed re-read.
 */
public class FeedSourceChangedException extends IllegalStateException {

    public FeedSourceChangedException(long feedId) {
        super("Feed " + feedId + " changed source after it was read; retry with the feed re-read.");
    }
}
