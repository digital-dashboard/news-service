package com.j11a.argus.config;

import com.j11a.argus.feed.fetch.FetchProperties;
import com.j11a.argus.feed.parse.FeedParser;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ArgusProperties.class, FetchProperties.class})
public class ArgusConfiguration {

    @Bean
    FeedParser feedParser() {
        return new FeedParser();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
