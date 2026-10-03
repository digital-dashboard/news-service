package com.j11a.argus.feed.fetch;

import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class RetryingFeedFetcher {

    private final RetryableFeedFetcher delegate;

    RetryingFeedFetcher(RetryableFeedFetcher delegate) {
        this.delegate = delegate;
    }

    public FetchResult fetch(URI url, FetchValidators validators, String sourceKey) {
        try {
            return delegate.fetch(url, validators, sourceKey);
        } catch (RetryableFetchException ex) {
            return ex.toFailedResult();
        }
    }
}
