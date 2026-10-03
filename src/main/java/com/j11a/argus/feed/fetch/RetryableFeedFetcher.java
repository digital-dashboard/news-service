package com.j11a.argus.feed.fetch;

import java.net.URI;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
class RetryableFeedFetcher {

    private final FeedFetcher fetcher;

    RetryableFeedFetcher(FeedFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Retryable(
            includes = RetryableFetchException.class,
            maxRetriesString = "${argus.fetch.retry.max-retries:2}",
            delayString = "${argus.fetch.retry.delay:1s}",
            multiplierString = "${argus.fetch.retry.multiplier:2.0}",
            timeoutString = "${argus.fetch.retry.timeout:25s}"
    )
    public FetchResult fetch(URI url, FetchValidators validators, String sourceKey) {
        return fetcher.fetchRetryable(url, validators, sourceKey);
    }
}
