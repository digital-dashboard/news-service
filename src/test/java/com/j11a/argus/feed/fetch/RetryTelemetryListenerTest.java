package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.net.URI;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.resilience.retry.MethodRetryEvent;

class RetryTelemetryListenerTest {

    private MeterRegistry registry;
    private RetryTelemetryListener listener;
    private Method fetchMethod;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        registry = new SimpleMeterRegistry();
        listener = new RetryTelemetryListener(registry);
        fetchMethod = RetryableFeedFetcher.class.getMethod("fetch", URI.class, FetchValidators.class, String.class);
    }

    @Test
    void incrementsFetchRetryCounterWhenRetryIsAttempted() {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(fetchMethod);
        when(invocation.getArguments()).thenReturn(new Object[] {URI.create("https://example.test"), FetchValidators.EMPTY, "my-source"});

        MethodRetryEvent event = new MethodRetryEvent(invocation, new RuntimeException("503"), false);
        listener.onMethodRetry(event);

        assertThat(registry.get(MetricNames.FETCH_RETRY).tags("source", "my-source").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void doesNotIncrementWhenRetryIsAborted() {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(fetchMethod);
        when(invocation.getArguments()).thenReturn(new Object[] {URI.create("https://example.test"), FetchValidators.EMPTY, "my-source"});

        MethodRetryEvent event = new MethodRetryEvent(invocation, new RuntimeException("aborted"), true);
        listener.onMethodRetry(event);

        assertThat(registry.find(MetricNames.FETCH_RETRY).counter()).isNull();
    }

    @Test
    void ignoresEventsForUnrelatedMethods() throws NoSuchMethodException {
        Method otherMethod = Object.class.getMethod("toString");
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(otherMethod);
        when(invocation.getArguments()).thenReturn(new Object[] {});

        MethodRetryEvent event = new MethodRetryEvent(invocation, new RuntimeException("err"), false);
        listener.onMethodRetry(event);

        assertThat(registry.find(MetricNames.FETCH_RETRY).counter()).isNull();
    }
}
