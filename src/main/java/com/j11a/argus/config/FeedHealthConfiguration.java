package com.j11a.argus.config;

import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.poll.PollProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the gauges here because feed.health must not depend on the poll package that owns PollProperties. */
@Configuration(proxyBeanMethods = false)
class FeedHealthConfiguration {

    @Bean
    FeedHealthGauges feedHealthGauges(JdbcClient jdbc, PollProperties poll, Clock clock, MeterRegistry registry) {
        return new FeedHealthGauges(jdbc, poll.failingThreshold(), clock, registry);
    }
}
