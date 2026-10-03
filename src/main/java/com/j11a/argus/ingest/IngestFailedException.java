package com.j11a.argus.ingest;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;

/**
 * An unexpected failure that FeedIngestService has already logged in full (fields and stack trace) and counted against
 * the feed. The cause is the original exception. As an API error it answers like any unexpected exception, a 500.
 */
public class IngestFailedException extends ApiException {

    private static final String DETAIL = "An unexpected error occurred.";

    private final long feedId;
    private final String reason;

    public IngestFailedException(long feedId, String reason, Throwable cause) {
        super(ErrorCode.INTERNAL_ERROR, DETAIL);
        initCause(cause);
        this.feedId = feedId;
        this.reason = reason;
    }

    public long feedId() {
        return feedId;
    }

    public String reason() {
        return reason;
    }
}
