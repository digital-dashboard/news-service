package com.j11a.argus.feed.fetch;

import org.jspecify.annotations.Nullable;

public class RetryableFetchException extends RuntimeException {

    private final FetchFailureReason reason;
    private final @Nullable Integer status;
    private final transient FetchError error;

    /** A transient failure with no exception behind it, such as a 5xx answer. */
    public RetryableFetchException(FetchFailureReason reason, @Nullable Integer status, FetchError error) {
        super("Retryable fetch failure: " + reason + (status != null ? " (" + status + ")" : ""));
        this.reason = reason;
        this.status = status;
        this.error = error;
    }

    /** A transient failure caused by an exception, which is kept as the cause. */
    public RetryableFetchException(FetchFailureReason reason, Throwable cause) {
        super("Retryable fetch failure: " + reason, cause);
        this.reason = reason;
        this.status = null;
        this.error = FetchError.of(cause);
    }

    public FetchFailureReason reason() {
        return reason;
    }

    public @Nullable Integer status() {
        return status;
    }

    public FetchError error() {
        return error;
    }

    public FetchResult.Failed toFailedResult() {
        return new FetchResult.Failed(reason, status, error);
    }
}
