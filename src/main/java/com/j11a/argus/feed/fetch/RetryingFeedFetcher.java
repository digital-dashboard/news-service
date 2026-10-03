package com.j11a.argus.feed.fetch;

import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;

/**
 * Retries one whole fetch, redirects included. The policy is built once from {@link FetchProperties.Retry}; each call
 * gets its own cheap template so the retry listener can tag the counter with that call's source. The overall timeout
 * is checked between attempts only, so one attempt can still run for the HTTP read timeout.
 */
@Slf4j
@Component
public class RetryingFeedFetcher {

    private final FeedFetcher fetcher;
    private final MeterRegistry meters;
    private final RetryPolicy policy;
    private final int maxAttempts;

    public RetryingFeedFetcher(FeedFetcher fetcher, FetchProperties properties, MeterRegistry meters) {
        this.fetcher = fetcher;
        this.meters = meters;
        FetchProperties.Retry retry = properties.retry();
        this.maxAttempts = retry.maxRetries() + 1;
        this.policy = RetryPolicy.builder()
                .includes(RetryableFetchException.class)
                .maxRetries(retry.maxRetries())
                .delay(retry.delay())
                .multiplier(retry.multiplier())
                .timeout(retry.timeout())
                .build();
    }

    /**
     * Once retries are exhausted, time out or are interrupted, the last failure becomes a Failed result. An
     * interrupted caller keeps its interrupt flag set.
     */
    public FetchResult fetch(URI url, FetchValidators validators, String sourceKey) {
        RetryTemplate template = new RetryTemplate(policy);
        template.setRetryListener(new RetryCounter(sourceKey));
        Retryable<FetchResult> attempt = () -> fetcher.fetchRetryable(url, validators);
        try {
            return template.execute(attempt);
        } catch (RetryException e) {
            return switch (e.getCause()) {
                case RetryableFetchException last -> last.toFailedResult();
                case RuntimeException unexpected -> throw unexpected;
                default -> throw new IllegalStateException("Unexpected fetch failure", e.getCause());
            };
        }
    }

    private final class RetryCounter implements RetryListener {

        private final String sourceKey;

        private RetryCounter(String sourceKey) {
            this.sourceKey = sourceKey;
        }

        @Override
        public void beforeRetry(RetryPolicy retryPolicy, Retryable<?> retryable, RetryState retryState) {
            meters.counter(MetricNames.FETCH_RETRY, MetricNames.Tags.SOURCE, sourceKey).increment();
            // The policy only retries RetryableFetchException, so that is all the listener can see.
            logRetry(retryState.getExceptions().size(), (RetryableFetchException) retryState.getLastException());
        }

        private void logRetry(int failedAttempt, RetryableFetchException failure) {
            String reason = failure.reason().tag();
            String errorType = failure.error().type();
            log.atInfo()
                    .setMessage("Fetch attempt " + failedAttempt + "/" + maxAttempts + " for " + sourceKey
                            + " failed (" + reason + " " + errorType + "), retrying")
                    .addKeyValue(LogKeys.SOURCE_KEY, sourceKey)
                    .addKeyValue(LogKeys.ATTEMPT, failedAttempt)
                    .addKeyValue(LogKeys.MAX_ATTEMPTS, maxAttempts)
                    .addKeyValue(LogKeys.REASON, reason)
                    .addKeyValue(LogKeys.ERROR_TYPE, errorType)
                    .log();
        }
    }
}
