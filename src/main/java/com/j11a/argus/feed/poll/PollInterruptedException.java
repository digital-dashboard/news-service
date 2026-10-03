package com.j11a.argus.feed.poll;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;

/**
 * A poll that was cut short by shutdown. It is an ApiException so a manual refresh-all answers 503 through the
 * existing handler; the scheduler and PollingTelemetry treat it as an outcome, not a failure.
 */
public class PollInterruptedException extends ApiException {

    private static final String DETAIL = "The poll was interrupted by shutdown.";

    public PollInterruptedException(Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, DETAIL);
        initCause(cause);
    }
}
