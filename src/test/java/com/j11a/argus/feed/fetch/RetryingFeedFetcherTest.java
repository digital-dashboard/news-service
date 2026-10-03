package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.fetch.FetchResult.Failed;
import com.j11a.argus.testsupport.FeedStubServer;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

class RetryingFeedFetcherTest {

    @Configuration(proxyBeanMethods = false)
    @EnableResilientMethods
    @EnableConfigurationProperties(FetchProperties.class)
    @Import({RetryingFeedFetcher.class, RetryableFeedFetcher.class})
    static class TestConfig {

        @Bean
        FeedFetcher feedFetcher(RestClient.Builder builder, FetchProperties properties) {
            return new FeedFetcher(builder, properties);
        }
    }

    private FeedStubServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class,
                    RestClientAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "argus.fetch.retry.max-retries=2",
                    "argus.fetch.retry.delay=10ms",
                    "argus.fetch.retry.multiplier=1.5",
                    "argus.fetch.retry.timeout=2s",
                    "spring.http.clients.read-timeout=200ms"
            );

    @BeforeEach
    void startServer() throws IOException {
        server = new FeedStubServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void status503IsRetriedTwiceThenGivesFailedWithExactlyThreeRequests() {
        server.serve("/down", 503, "text/plain", FeedStubServer.utf8("unavailable"));

        runner.run(context -> {
            RetryingFeedFetcher fetcher = context.getBean(RetryingFeedFetcher.class);
            FetchResult result = fetcher.fetch(URI.create(server.baseUrl() + "/down"), FetchValidators.EMPTY, "test-source");

            assertThat(result).isEqualTo(new Failed(FetchFailureReason.HTTP_STATUS, 503));
            assertThat(server.requestsTo("/down")).hasSize(3);
        });
    }

    @Test
    void status404IsNeverRetried() {
        server.serve("/missing", 404, "text/plain", FeedStubServer.utf8("not found"));

        runner.run(context -> {
            RetryingFeedFetcher fetcher = context.getBean(RetryingFeedFetcher.class);
            FetchResult result = fetcher.fetch(URI.create(server.baseUrl() + "/missing"), FetchValidators.EMPTY, "test-source");

            assertThat(result).isEqualTo(new Failed(FetchFailureReason.HTTP_STATUS, 404));
            assertThat(server.requestsTo("/missing")).hasSize(1);
        });
    }

    @Test
    void timeoutIsRetried() {
        server.stallBeforeHeaders("/slow", 500);

        runner.run(context -> {
            RetryingFeedFetcher fetcher = context.getBean(RetryingFeedFetcher.class);
            FetchResult result = fetcher.fetch(URI.create(server.baseUrl() + "/slow"), FetchValidators.EMPTY, "test-source");

            assertThat(result).isEqualTo(new Failed(FetchFailureReason.TIMEOUT, null));
            assertThat(server.requestsTo("/slow")).hasSize(3);
        });
    }

    @Test
    void redirectsAreNotRetriedPerHop() {
        server.redirect("/hop1", 302, "/hop2")
                .serve("/hop2", 503, "text/plain", FeedStubServer.utf8("unavailable"));

        runner.run(context -> {
            RetryingFeedFetcher fetcher = context.getBean(RetryingFeedFetcher.class);
            FetchResult result = fetcher.fetch(URI.create(server.baseUrl() + "/hop1"), FetchValidators.EMPTY, "test-source");

            assertThat(result).isEqualTo(new Failed(FetchFailureReason.HTTP_STATUS, 503));
            // 3 attempts total, each attempt requests /hop1 then /hop2
            assertThat(server.requestsTo("/hop1")).hasSize(3);
            assertThat(server.requestsTo("/hop2")).hasSize(3);
        });
    }

    @Test
    void overallTimeoutBudgetIsRespected() {
        server.serve("/down", 503, "text/plain", FeedStubServer.utf8("unavailable"));

        // With 50ms delay and 25ms timeout budget, the second attempt will exceed the 25ms budget
        runner.withPropertyValues(
                "argus.fetch.retry.max-retries=5",
                "argus.fetch.retry.delay=50ms",
                "argus.fetch.retry.timeout=25ms"
        ).run(context -> {
            RetryingFeedFetcher fetcher = context.getBean(RetryingFeedFetcher.class);
            FetchResult result = fetcher.fetch(URI.create(server.baseUrl() + "/down"), FetchValidators.EMPTY, "test-source");

            assertThat(result).isEqualTo(new Failed(FetchFailureReason.HTTP_STATUS, 503));
            // Should abort before exhausting all 5 retries (1 initial + at most 1 retry)
            assertThat(server.requestsTo("/down").size()).isLessThan(4);
        });
    }
}
