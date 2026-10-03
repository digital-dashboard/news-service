package com.j11a.argus.feed.fetch;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.resilience.retry.MethodRetryEvent;
import org.springframework.stereotype.Component;

@Component
public class RetryTelemetryListener {

    private final MeterRegistry registry;

    public RetryTelemetryListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onMethodRetry(MethodRetryEvent event) {
        if (!event.isRetryAborted()
                && "fetch".equals(event.getMethod().getName())
                && (event.getMethod().getDeclaringClass() == RetryableFeedFetcher.class
                    || event.getMethod().getDeclaringClass() == RetryingFeedFetcher.class)) {
            Object[] args = event.getSource().getArguments();
            if (args.length > 2 && args[2] instanceof String sourceKey) {
                registry.counter(MetricNames.FETCH_RETRY, MetricNames.Tags.SOURCE, sourceKey).increment();
            }
        }
    }
}
