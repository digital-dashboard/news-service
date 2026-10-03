package com.j11a.argus.config;

import com.j11a.argus.feed.health.FailingThreshold;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.poll.PollProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the threshold and gauges here because neither feed.health nor ingest may depend on feed.poll. */
@Configuration(proxyBeanMethods = false)
class FeedHealthConfiguration {

    @Bean
    FailingThreshold failingThreshold(PollProperties poll) {
        return new FailingThreshold(poll.failingThreshold());
    }

    @Bean
    FeedHealthGauges feedHealthGauges(JdbcClient jdbc, PollProperties poll, Clock clock, MeterRegistry registry) {
        return new FeedHealthGauges(jdbc, poll.failingThreshold(), clock, registry);
    }
}
