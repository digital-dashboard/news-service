package com.j11a.argus.source;

/**
 * The feed moved to a third source between the caller's read and the locks. The transaction holds the wrong pair of
 * source locks and cannot take another in order, so it rolls back and the caller may retry.
 */
public class FeedSourceChangedException extends IllegalStateException {

    public FeedSourceChangedException(long feedId) {
        super("Feed " + feedId + " changed source while it was being moved; retry the move.");
    }
}
