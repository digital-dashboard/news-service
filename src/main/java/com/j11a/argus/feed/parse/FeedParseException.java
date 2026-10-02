package com.j11a.argus.feed.parse;

/** The message names the reason only; upstream text never goes into it. */
public final class FeedParseException extends Exception {

    public enum Reason { MALFORMED_XML, NOT_A_FEED, EMPTY }

    private final Reason reason;

    public FeedParseException(Reason reason) {
        super("Feed could not be parsed: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
