package com.j11a.argus.integration;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Collects every finished span in memory, so tests can assert on names, parents and attributes. */
@TestConfiguration(proxyBeanMethods = false)
public class SpanCollectorConfig {

    public static final class CollectingSpanProcessor implements SpanProcessor {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        public List<SpanData> spans() {
            return List.copyOf(spans);
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            spans.add(span.toSpanData());
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }
    }

    @Bean
    CollectingSpanProcessor collectingSpanProcessor() {
        return new CollectingSpanProcessor();
    }
}
