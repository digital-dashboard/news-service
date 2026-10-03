package com.j11a.argus.config;

import com.j11a.argus.feed.fetch.FetchProperties;
import com.j11a.argus.feed.parse.FeedParser;
import com.j11a.argus.feed.poll.PollProperties;
import com.j11a.argus.ingest.dedup.EntryDedupResolver;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableResilientMethods // @ConcurrencyLimit on FeedPoller
@EnableConfigurationProperties({ArgusProperties.class, FetchProperties.class, PollProperties.class})
public class ArgusConfiguration {

    /** On shutdown, how long closing the poll executor waits for workers to leave after interrupting them. */
    static final long POLL_TERMINATION_TIMEOUT_MILLIS = 10_000;

    static {
        // Neither Boot nor context-propagation registers the MDC accessor, and without it the task decorator would
        // not carry pollId, feedId and sourceId onto the poll worker threads.
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
    }

    @Bean
    FeedParser feedParser() {
        return new FeedParser();
    }

    @Bean
    EntryDedupResolver entryDedupResolver() {
        return new EntryDedupResolver();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public SimpleAsyncTaskExecutor pollExecutor(PollProperties properties) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("argus-poll-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(properties.concurrency());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        // Interrupt the workers as soon as the executor closes instead of waiting for the poll to finish.
        executor.setCancelRemainingTasksOnClose(true);
        executor.setTaskTerminationTimeout(POLL_TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }
}
