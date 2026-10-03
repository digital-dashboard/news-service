package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.fetch.FetchResult.Fetched;
import com.j11a.argus.testsupport.FeedStubServer;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Builds the fetcher from the real application.yml and Boot's RestClient.Builder, as production does. */
class FeedFetcherWiringTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FetchProperties.class)
    static class FetcherConfiguration {

        @Bean
        FeedFetcher feedFetcher(RestClient.Builder builder, FetchProperties properties) {
            return new FeedFetcher(builder, properties);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(FetcherConfiguration.class);

    @Test
    void applicationYamlLeavesRedirectsToTheFetcherSoItCanReportThem() throws Exception {
        try (FeedStubServer server = new FeedStubServer()) {
            server.redirect("/old", 301, "/new").serve("/new", 200, "application/rss+xml", FeedStubServer.utf8("<rss/>"));

            runner.run(context -> {
                FetchResult result = context.getBean(FeedFetcher.class).fetch(URI.create(server.baseUrl() + "/old"));

                assertThat(result).isInstanceOfSatisfying(Fetched.class, fetched -> {
                    assertThat(fetched.permanentTarget()).isEqualTo(URI.create(server.baseUrl() + "/new"));
                    assertThat(fetched.finalUrl().getPath()).isEqualTo("/new");
                });
                assertThat(server.requestsTo("/old")).hasSize(1);
                assertThat(server.requestsTo("/new")).hasSize(1);
                assertThat(server.requestsTo("/new").get(0).header("User-Agent")).startsWith("Argus/");
            });
        }
    }

    @Test
    void applicationYamlReadTimeoutBoundsASlowBody() throws Exception {
        try (FeedStubServer server = new FeedStubServer()) {
            server.drip("/slow", "application/rss+xml", FeedStubServer.utf8("<rss>slow</rss>"), 1, 3_000);

            runner.withPropertyValues("spring.http.clients.read-timeout=500ms").run(context -> {
                FetchResult result = context.getBean(FeedFetcher.class).fetch(URI.create(server.baseUrl() + "/slow"));

                FetchAssertions.assertFailed(result, FetchFailureReason.TIMEOUT, null);
            });
        }
    }
}
