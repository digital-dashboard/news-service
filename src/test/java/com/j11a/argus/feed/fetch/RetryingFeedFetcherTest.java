package com.j11a.argus.feed.fetch;

import static com.j11a.argus.feed.fetch.FetchAssertions.assertFailed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.j11a.argus.feed.fetch.FetchResult.Failed;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.testsupport.FeedStubServer;
import com.j11a.argus.testsupport.LogCapture;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

class RetryingFeedFetcherTest {

    private static final String SOURCE = "test-source";
    private static final String DOWN = "/down";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(200);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(5);

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private FeedStubServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FeedStubServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
        Thread.interrupted();
    }

    private static FetchProperties properties(int maxRetries, Duration delay, Duration timeout) {
        return new FetchProperties("Argus-Test/1.0", DataSize.ofKilobytes(64), 5,
                new FetchProperties.Retry(maxRetries, delay, 1.5, timeout));
    }

    private RetryingFeedFetcher retrying(int maxRetries, Duration delay, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        FetchProperties properties = properties(maxRetries, delay, timeout);
        FeedFetcher fetcher = new FeedFetcher(RestClient.builder().requestFactory(factory), properties);
        return new RetryingFeedFetcher(fetcher, properties, meters);
    }

    private FetchResult fetch(RetryingFeedFetcher fetcher, String path) {
        return fetcher.fetch(URI.create(server.baseUrl() + path), FetchValidators.EMPTY, SOURCE);
    }

    private double retries() {
        Counter counter = meters.find(MetricNames.FETCH_RETRY).tag(MetricNames.Tags.SOURCE, SOURCE).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void status503IsRetriedTwiceGivingExactlyThreeRequestsAndTwoCountedRetries() {
        server.serve(DOWN, 503, "text/plain", FeedStubServer.utf8("unavailable"));

        FetchResult result = fetch(retrying(2, Duration.ofMillis(10), LONG_TIMEOUT), DOWN);

        assertFailed(result, FetchFailureReason.HTTP_STATUS, 503);
        assertThat(server.requestsTo(DOWN)).hasSize(3);
        assertThat(retries()).isEqualTo(2.0);
    }

    @Test
    void zeroMaxRetriesMakesOneRequestAndCountsNoRetry() {
        server.serve(DOWN, 503, "text/plain", FeedStubServer.utf8("unavailable"));

        FetchResult result = fetch(retrying(0, Duration.ofMillis(10), LONG_TIMEOUT), DOWN);

        assertFailed(result, FetchFailureReason.HTTP_STATUS, 503);
        assertThat(server.requestsTo(DOWN)).hasSize(1);
        assertThat(retries()).isZero();
    }

    @Test
    void status404IsNeverRetried() {
        server.serve("/missing", 404, "text/plain", FeedStubServer.utf8("not found"));

        FetchResult result = fetch(retrying(2, Duration.ofMillis(10), LONG_TIMEOUT), "/missing");

        assertFailed(result, FetchFailureReason.HTTP_STATUS, 404);
        assertThat(server.requestsTo("/missing")).hasSize(1);
        assertThat(retries()).isZero();
    }

    @Test
    void aTimeoutIsRetried() {
        server.stallBeforeHeaders("/slow", 500);

        FetchResult result = fetch(retrying(2, Duration.ofMillis(10), LONG_TIMEOUT), "/slow");

        assertFailed(result, FetchFailureReason.TIMEOUT, null);
        assertThat(server.requestsTo("/slow")).hasSize(3);
        assertThat(retries()).isEqualTo(2.0);
    }

    @Test
    void aRetryRepeatsTheWholeRedirectChainRatherThanTheLastHop() {
        server.redirect("/hop1", 302, "/hop2")
                .serve("/hop2", 503, "text/plain", FeedStubServer.utf8("unavailable"));

        FetchResult result = fetch(retrying(2, Duration.ofMillis(10), LONG_TIMEOUT), "/hop1");

        assertFailed(result, FetchFailureReason.HTTP_STATUS, 503);
        assertThat(server.requestsTo("/hop1")).hasSize(3);
        assertThat(server.requestsTo("/hop2")).hasSize(3);
    }

    @Test
    void theOverallTimeoutStopsRetryingBeforeMaxRetriesIsReached() {
        server.serve(DOWN, 503, "text/plain", FeedStubServer.utf8("unavailable"));

        FetchResult result = fetch(retrying(10, Duration.ofMillis(300), Duration.ofMillis(400)), DOWN);

        assertFailed(result, FetchFailureReason.HTTP_STATUS, 503);
        // 1 initial request and 1 retry after 300ms; the next back-off (450ms) would exceed the 400ms budget.
        assertThat(server.requestsTo(DOWN)).hasSize(2);
        assertThat(retries()).isEqualTo(1.0);
    }

    @Test
    void anInterruptedBackOffStopsRetryingKeepsTheFlagAndReturnsTheLastFailure() {
        server.serve(DOWN, 503, "text/plain", FeedStubServer.utf8("unavailable"));
        RetryingFeedFetcher fetcher = retrying(2, Duration.ofSeconds(30), Duration.ofMinutes(5));
        Thread caller = Thread.currentThread();
        ScheduledExecutorService interrupter = Executors.newSingleThreadScheduledExecutor();
        interrupter.schedule(caller::interrupt, 300, TimeUnit.MILLISECONDS);

        FetchResult result;
        try {
            result = fetch(fetcher, DOWN);
        } finally {
            interrupter.shutdownNow();
        }

        assertFailed(result, FetchFailureReason.HTTP_STATUS, 503);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(server.requestsTo(DOWN)).hasSize(1);
        assertThat(retries()).isZero();
    }

    @Test
    void aFailureThatIsNotAFetchFailureIsRethrownAndNeverRetried() {
        FeedFetcher fetcher = mock(FeedFetcher.class);
        when(fetcher.fetchRetryable(any(), any())).thenThrow(new IllegalArgumentException("bug"));
        RetryingFeedFetcher retrying = new RetryingFeedFetcher(fetcher, properties(2, Duration.ofMillis(1), LONG_TIMEOUT), meters);

        assertThatThrownBy(() -> fetch(retrying, "/anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bug");
        assertThat(retries()).isZero();
    }

    @Test
    void anErrorIsWrappedAndNeverRetried() {
        FeedFetcher fetcher = mock(FeedFetcher.class);
        when(fetcher.fetchRetryable(any(), any())).thenThrow(new LinkageError("broken"));
        RetryingFeedFetcher retrying = new RetryingFeedFetcher(fetcher, properties(2, Duration.ofMillis(1), LONG_TIMEOUT), meters);

        assertThatThrownBy(() -> fetch(retrying, "/anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(LinkageError.class);
    }

    @Test
    void eachRetryLogsOneInfoLineNamingTheAttemptAndTheCause() {
        server.serve(DOWN, 503, "text/plain", FeedStubServer.utf8("unavailable"));

        try (LogCapture logs = LogCapture.start()) {
            fetch(retrying(2, Duration.ofMillis(10), LONG_TIMEOUT), DOWN);

            assertThat(logs.at(Level.INFO).stream()
                    .filter(event -> event.getLoggerName().equals(RetryingFeedFetcher.class.getName())))
                    .hasSize(2).satisfiesExactly(
                    first -> assertRetryLine(first, 1), second -> assertRetryLine(second, 2));
        }
    }

    private static void assertRetryLine(ILoggingEvent event, int attempt) {
        assertThat(LogCapture.keyValues(event))
                .containsEntry("sourceKey", SOURCE)
                .containsEntry("attempt", attempt)
                .containsEntry("maxAttempts", 3)
                .containsEntry("reason", "http_status")
                .containsEntry("errorType", "HttpStatus");
        assertThat(event.getFormattedMessage())
                .isEqualTo("Fetch attempt " + attempt + "/3 for test-source failed (http_status HttpStatus), retrying");
    }

    @Test
    void anExhaustedRetryReturnsTheLastFailureWithItsError() {
        server.serve(DOWN, 503, "text/plain", FeedStubServer.utf8("unavailable"));

        FetchResult result = fetch(retrying(1, Duration.ofMillis(10), LONG_TIMEOUT), DOWN);

        Failed failed = assertFailed(result, FetchFailureReason.HTTP_STATUS, 503);
        assertThat(failed.error()).isEqualTo(new FetchError("HttpStatus", "503 Service Unavailable"));
    }

    @Test
    void aTimeoutAfterRetriesCarriesTheTimeoutExceptionType() {
        server.stallBeforeHeaders("/silent", READ_TIMEOUT.toMillis() * 10);

        FetchResult result = fetch(retrying(1, Duration.ofMillis(10), LONG_TIMEOUT), "/silent");

        Failed failed = assertFailed(result, FetchFailureReason.TIMEOUT, null);
        assertThat(failed.error()).extracting(FetchError::type).isEqualTo("HttpTimeoutException");
    }
}
