package com.j11a.argus.feed.fetch;

import org.jspecify.annotations.Nullable;

public class RetryableFetchException extends RuntimeException {

    private final FetchFailureReason reason;
    private final @Nullable Integer status;
    private final String sourceKey;

    public RetryableFetchException(FetchFailureReason reason, @Nullable Integer status, String sourceKey) {
        super("Retryable fetch failure: " + reason + (status != null ? " (" + status + ")" : ""));
        this.reason = reason;
        this.status = status;
        this.sourceKey = sourceKey;
    }

    public FetchFailureReason reason() {
        return reason;
    }

    public @Nullable Integer status() {
        return status;
    }

    public String sourceKey() {
        return sourceKey;
    }

    public FetchResult.Failed toFailedResult() {
        return new FetchResult.Failed(reason, status);
    }
}
