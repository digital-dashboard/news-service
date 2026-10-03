package com.j11a.argus.feed.fetch;

import org.jspecify.annotations.Nullable;

public class RetryableFetchException extends RuntimeException {

    private final FetchFailureReason reason;
    private final @Nullable Integer status;

    public RetryableFetchException(FetchFailureReason reason, @Nullable Integer status) {
        super("Retryable fetch failure: " + reason + (status != null ? " (" + status + ")" : ""));
        this.reason = reason;
        this.status = status;
    }

    public FetchFailureReason reason() {
        return reason;
    }

    public @Nullable Integer status() {
        return status;
    }

    public FetchResult.Failed toFailedResult() {
        return new FetchResult.Failed(reason, status);
    }
}
