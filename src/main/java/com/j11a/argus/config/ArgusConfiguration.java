package com.j11a.argus.config;

import com.j11a.argus.feed.fetch.FetchProperties;
import com.j11a.argus.feed.parse.FeedParser;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableResilientMethods
@EnableConfigurationProperties({ArgusProperties.class, FetchProperties.class, PollProperties.class})
public class ArgusConfiguration {

    static {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
    }

    @Bean
    FeedParser feedParser() {
        return new FeedParser();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
