package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.feed.fetch.FetchResult.Failed;
import com.j11a.argus.feed.fetch.FetchResult.Fetched;
import com.j11a.argus.testsupport.FeedStubServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

class FeedFetcherTest {

    private static final FetchProperties.Retry RETRY =
            new FetchProperties.Retry(2, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(25));

    private static final String USER_AGENT = "Argus-Test/1.0 (fetcher test)";
    private static final String RSS = "application/rss+xml; charset=utf-8";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(600);
    private static final DataSize MAX_BODY = DataSize.ofKilobytes(1);

    private FeedStubServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FeedStubServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private FeedFetcher fetcher(int maxRedirects) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return new FeedFetcher(RestClient.builder().requestFactory(factory),
                new FetchProperties(USER_AGENT, MAX_BODY, maxRedirects, RETRY));
    }

    private FetchResult fetch(String path) {
        return fetcher(5).fetch(URI.create(server.baseUrl() + path));
    }

    private static void assertFailed(FetchResult result, FetchFailureReason reason, Integer status) {
        assertThat(result).isEqualTo(new Failed(reason, status));
    }

    @Test
    void returnsBodyContentTypeAndFinalUrlOnSuccess() {
        server.serve("/feed", 200, RSS, FeedStubServer.utf8("<rss/>"));

        FetchResult result = fetch("/feed");

        assertThat(result).isInstanceOfSatisfying(Fetched.class, fetched -> {
            assertThat(new String(fetched.body())).isEqualTo("<rss/>");
            assertThat(fetched.contentType()).isEqualTo(RSS);
            assertThat(fetched.finalUrl()).isEqualTo(URI.create(server.baseUrl() + "/feed"));
            assertThat(fetched.permanentTarget()).isNull();
            assertThat(fetched.validators()).isEqualTo(FetchValidators.EMPTY);
        });
    }

    @Test
    void capturesValidatorsFromResponseHeadersOn200() {
        server.serve("/feed", 200, RSS, FeedStubServer.utf8("<rss/>"),
                Map.of("ETag", "\"abc\"", "Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT"));

        FetchResult result = fetch("/feed");

        assertThat(result).isInstanceOfSatisfying(Fetched.class, fetched -> {
            assertThat(fetched.validators().etag()).isEqualTo("\"abc\"");
            assertThat(fetched.validators().lastModified()).isEqualTo("Wed, 21 Oct 2026 07:28:00 GMT");
        });
    }

    @Test
    void status304ReturnsNotModifiedWithValidators() {
        server.serve("/feed", 304, null, new byte[0],
                Map.of("ETag", "\"etag-304\"", "Last-Modified", "Thu, 22 Oct 2026 08:00:00 GMT"));

        FetchResult result = fetcher(5).fetch(URI.create(server.baseUrl() + "/feed"),
                new FetchValidators("\"etag-304\"", "Thu, 22 Oct 2026 08:00:00 GMT"));

        assertThat(result).isInstanceOfSatisfying(FetchResult.NotModified.class, notModified -> {
            assertThat(notModified.finalUrl()).isEqualTo(URI.create(server.baseUrl() + "/feed"));
            assertThat(notModified.permanentTarget()).isNull();
            assertThat(notModified.validators().etag()).isEqualTo("\"etag-304\"");
            assertThat(notModified.validators().lastModified()).isEqualTo("Thu, 22 Oct 2026 08:00:00 GMT");
        });
    }

    @Test
    void sendsTheStoredValidatorsOnEveryHop() {
        server.redirect("/old", 302, "/new")
                .serve("/new", 200, RSS, FeedStubServer.utf8("<rss/>"));

        fetcher(5).fetch(URI.create(server.baseUrl() + "/old"),
                new FetchValidators("\"old-etag\"", "Tue, 20 Oct 2026 00:00:00 GMT"));

        for (String path : new String[] {"/old", "/new"}) {
            FeedStubServer.Request hop = server.requestsTo(path).get(0);
            assertThat(hop.header("If-None-Match")).isEqualTo("\"old-etag\"");
            assertThat(hop.header("If-Modified-Since")).isEqualTo("Tue, 20 Oct 2026 00:00:00 GMT");
            assertThat(hop.header("User-Agent")).isEqualTo(USER_AGENT);
        }
    }

    @Test
    void aPermanentRedirectThatThenAnswers304IsNotModifiedWithThePermanentTarget() {
        FetchValidators stored = new FetchValidators("\"v1\"", null);
        server.redirect("/feed", 301, "/final")
                .serve("/final", 304, null, new byte[0], Map.of("ETag", "\"v1\""));

        FetchResult result = fetcher(5).fetch(URI.create(server.baseUrl() + "/feed"), stored);

        assertThat(server.requestsTo("/final").get(0).header("If-None-Match")).isEqualTo("\"v1\"");
        assertThat(result).isInstanceOfSatisfying(FetchResult.NotModified.class, notModified -> {
            assertThat(notModified.finalUrl()).isEqualTo(URI.create(server.baseUrl() + "/final"));
            assertThat(notModified.permanentTarget()).isEqualTo(URI.create(server.baseUrl() + "/final"));
        });
    }

    @Test
    void a304ToARequestThatSentNoValidatorsIsAnHttpStatusFailure() {
        server.serve("/feed", 304, null, new byte[0]);

        assertFailed(fetch("/feed"), FetchFailureReason.HTTP_STATUS, 304);
    }

    @Test
    void retryableFetchThrowsForAServerErrorAndForATransientIoFailure() throws IOException {
        server.serve("/down", 503, "text/plain", FeedStubServer.utf8("unavailable"));
        FeedFetcher fetcher = fetcher(5);
        URI down = URI.create(server.baseUrl() + "/down");
        URI refused = URI.create(deadBaseUrl() + "/feed");

        assertThatThrownBy(() -> fetcher.fetchRetryable(down, FetchValidators.EMPTY))
                .isInstanceOfSatisfying(RetryableFetchException.class, e -> {
                    assertThat(e.reason()).isEqualTo(FetchFailureReason.HTTP_STATUS);
                    assertThat(e.status()).isEqualTo(503);
                });
        assertThatThrownBy(() -> fetcher.fetchRetryable(refused, FetchValidators.EMPTY))
                .isInstanceOfSatisfying(RetryableFetchException.class,
                        e -> assertThat(e.toFailedResult()).isEqualTo(new Failed(FetchFailureReason.IO, null)));
    }

    @Test
    void bodyExactlyAtTheLimitIsAccepted() {
        byte[] body = new byte[(int) MAX_BODY.toBytes()];
        Arrays.fill(body, (byte) 'a');
        server.serve("/exact", 200, RSS, body);

        assertThat(fetch("/exact")).isInstanceOfSatisfying(Fetched.class,
                fetched -> assertThat(fetched.body()).hasSize(body.length));
    }

    @Test
    void sendsConfiguredUserAgentAndAcceptHeaders() {
        server.serve("/feed", 200, RSS, FeedStubServer.utf8("<rss/>"));

        fetch("/feed");

        FeedStubServer.Request request = server.requestsTo("/feed").get(0);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.header("User-Agent")).isEqualTo(USER_AGENT);
        assertThat(request.header("Accept")).isEqualTo(FeedFetcher.ACCEPT);
    }

    @Test
    void notFoundIsAnHttpStatusFailure() {
        server.serve("/missing", 404, "text/plain", FeedStubServer.utf8("nope"));

        assertFailed(fetch("/missing"), FetchFailureReason.HTTP_STATUS, 404);
    }

    @Test
    void serviceUnavailableIsAnHttpStatusFailureWithoutARetry() {
        server.serve("/down", 503, "text/plain", FeedStubServer.utf8("busy"));

        assertFailed(fetch("/down"), FetchFailureReason.HTTP_STATUS, 503);
        assertThat(server.requestsTo("/down")).hasSize(1);
    }

    @Test
    void declaredContentLengthOverTheLimitIsRejected() {
        server.serve("/big", 200, RSS, new byte[(int) MAX_BODY.toBytes() + 1]);

        assertFailed(fetch("/big"), FetchFailureReason.TOO_LARGE, null);
    }

    @Test
    void chunkedBodyOverTheLimitIsRejectedWhileStreaming() {
        server.serveChunked("/chunked", RSS, new byte[(int) MAX_BODY.toBytes() * 4]);

        assertFailed(fetch("/chunked"), FetchFailureReason.TOO_LARGE, null);
    }

    @Test
    void aBodyDrippedFasterThanTheReadTimeoutStillEndsInATimeoutAtTheTotalDeadline() {
        byte[] body = FeedStubServer.utf8("<rss>dripping slowly, one byte at a time</rss>");
        long pauseMillis = READ_TIMEOUT.toMillis() / 3;
        assertThat(pauseMillis * body.length).isGreaterThan(READ_TIMEOUT.multipliedBy(2).toMillis());
        server.drip("/drip", RSS, body, 1, pauseMillis);
        long start = System.nanoTime();

        FetchResult result = fetch("/drip");

        assertFailed(result, FetchFailureReason.TIMEOUT, null);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(READ_TIMEOUT.multipliedBy(2));
    }

    @Test
    void serverThatStallsBeforeSendingHeadersEndsInATimeout() {
        server.stallBeforeHeaders("/silent", READ_TIMEOUT.toMillis() * 10);

        assertFailed(fetch("/silent"), FetchFailureReason.TIMEOUT, null);
    }

    private static String deadBaseUrl() throws IOException {
        try (FeedStubServer closed = new FeedStubServer()) {
            return closed.baseUrl();
        }
    }

    @Test
    void connectionRefusedIsAnIoFailure() throws IOException {
        FetchResult result = fetcher(5).fetch(URI.create(deadBaseUrl() + "/feed"));

        assertFailed(result, FetchFailureReason.IO, null);
    }

    @Test
    void temporaryRedirectIsFollowedAndReportsTheFinalUrlWithoutAPermanentTarget() {
        server.redirect("/old", 302, "/new").serve("/new", 200, RSS, FeedStubServer.utf8("<rss/>"));

        FetchResult result = fetch("/old");

        assertThat(result).isInstanceOfSatisfying(Fetched.class, fetched -> {
            assertThat(fetched.finalUrl()).isEqualTo(URI.create(server.baseUrl() + "/new"));
            assertThat(fetched.permanentTarget()).isNull();
        });
        assertThat(server.requestsTo("/new")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 308})
    void permanentRedirectsAreFollowedAndReportTheTarget(int status) {
        server.redirect("/old", status, server.baseUrl() + "/new")
                .serve("/new", 200, RSS, FeedStubServer.utf8("<rss/>"));

        FetchResult result = fetch("/old");

        assertThat(result).isInstanceOfSatisfying(Fetched.class, fetched -> {
            assertThat(fetched.finalUrl()).isEqualTo(URI.create(server.baseUrl() + "/new"));
            assertThat(fetched.permanentTarget()).isEqualTo(URI.create(server.baseUrl() + "/new"));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {303, 307})
    void otherRedirectStatusesAreFollowedWithoutAPermanentTarget(int status) {
        server.redirect("/old", status, "/new").serve("/new", 200, RSS, FeedStubServer.utf8("<rss/>"));

        assertThat(fetch("/old")).isInstanceOfSatisfying(Fetched.class,
                fetched -> assertThat(fetched.permanentTarget()).isNull());
    }

    @Test
    void permanentTargetStopsAtTheFirstTemporaryHopOfTheLeadingChain() {
        server.redirect("/a", 301, "/b").redirect("/b", 302, "/c").serve("/c", 200, RSS, FeedStubServer.utf8("<rss/>"));

        assertThat(fetch("/a")).isInstanceOfSatisfying(Fetched.class, fetched -> {
            assertThat(fetched.permanentTarget()).isEqualTo(URI.create(server.baseUrl() + "/b"));
            assertThat(fetched.finalUrl().getPath()).isEqualTo("/c");
        });
    }

    @Test
    void aTemporaryFirstHopMeansNoPermanentTargetEvenIfALaterHopIsPermanent() {
        server.redirect("/a", 302, "/b").redirect("/b", 301, "/c").serve("/c", 200, RSS, FeedStubServer.utf8("<rss/>"));

        assertThat(fetch("/a")).isInstanceOfSatisfying(Fetched.class, fetched -> {
            assertThat(fetched.permanentTarget()).isNull();
            assertThat(fetched.finalUrl().getPath()).isEqualTo("/c");
        });
    }

    @Test
    void mixedPermanentStatusesKeepTheChainUnbroken() {
        server.redirect("/a", 301, "/b").redirect("/b", 308, "/c").serve("/c", 200, RSS, FeedStubServer.utf8("<rss/>"));

        assertThat(fetch("/a")).isInstanceOfSatisfying(Fetched.class, fetched ->
                assertThat(fetched.permanentTarget()).isEqualTo(URI.create(server.baseUrl() + "/c")));
    }

    @Test
    void relativeLocationAgainstAnEmptyPathRequestsTheRootedUrl() {
        server.redirect("/", 302, "feed.xml").serve("/feed.xml", 200, RSS, FeedStubServer.utf8("<rss/>"));

        FetchResult result = fetcher(5).fetch(URI.create(server.baseUrl()));

        assertThat(result).isInstanceOfSatisfying(Fetched.class, fetched ->
                assertThat(fetched.finalUrl()).isEqualTo(URI.create(server.baseUrl() + "/feed.xml")));
        assertThat(server.requestsTo("/feed.xml")).hasSize(1);
    }

    @Test
    void userInfoOnTheInitialUrlIsInvalidAndNoRequestIsMade() {
        URI withCredentials = URI.create(server.baseUrl().replace("http://", "http://user:pass@") + "/feed");

        FetchResult result = fetcher(5).fetch(withCredentials);

        assertFailed(result, FetchFailureReason.INVALID_URL, null);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void userInfoOnARedirectHopIsInvalidAndThatHopIsNeverRequested() {
        String target = server.baseUrl().replace("http://", "http://user:pass@") + "/secret";
        server.redirect("/bounce", 302, target).serve("/secret", 200, RSS, FeedStubServer.utf8("<rss/>"));

        assertFailed(fetch("/bounce"), FetchFailureReason.INVALID_URL, null);
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void redirectChainWithinTheLimitSucceeds() {
        server.redirect("/r0", 302, "/r1").redirect("/r1", 302, "/r2").serve("/r2", 200, RSS, FeedStubServer.utf8("x"));

        assertThat(fetcher(2).fetch(URI.create(server.baseUrl() + "/r0"))).isInstanceOf(Fetched.class);
    }

    @Test
    void redirectChainBeyondTheLimitIsRejected() {
        server.redirect("/r0", 302, "/r1").redirect("/r1", 302, "/r2").redirect("/r2", 302, "/r3")
                .serve("/r3", 200, RSS, FeedStubServer.utf8("x"));

        FetchResult result = fetcher(2).fetch(URI.create(server.baseUrl() + "/r0"));

        assertThat(result).isInstanceOfSatisfying(Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo(FetchFailureReason.REDIRECT_LIMIT));
        assertThat(server.requestsTo("/r3")).isEmpty();
    }

    @Test
    void redirectLoopStopsAtTheLimit() {
        server.redirect("/loop", 302, "/loop");

        FetchResult result = fetcher(3).fetch(URI.create(server.baseUrl() + "/loop"));

        assertThat(result).isInstanceOfSatisfying(Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo(FetchFailureReason.REDIRECT_LIMIT));
        assertThat(server.requestsTo("/loop")).hasSize(4);
    }

    @Test
    void zeroRedirectsAllowedRejectsTheFirstRedirect() {
        server.redirect("/old", 301, "/new");

        FetchResult result = fetcher(0).fetch(URI.create(server.baseUrl() + "/old"));

        assertThat(result).isInstanceOfSatisfying(Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo(FetchFailureReason.REDIRECT_LIMIT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://files.example.test/feed.xml", "file:///etc/passwd", "gopher://x.example.test/"})
    void redirectToANonHttpSchemeIsInvalid(String target) {
        server.redirect("/bounce", 302, target);

        assertFailed(fetch("/bounce"), FetchFailureReason.INVALID_URL, null);
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void redirectWithAnUnparseableLocationIsInvalid() {
        server.redirect("/bounce", 302, "http://bad host/x");

        assertFailed(fetch("/bounce"), FetchFailureReason.INVALID_URL, null);
    }

    @Test
    void redirectWithoutALocationIsAnHttpStatusFailure() {
        server.serve("/bounce", 302, null, new byte[0]);

        assertFailed(fetch("/bounce"), FetchFailureReason.HTTP_STATUS, 302);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://files.example.test/feed.xml", "file:///etc/passwd", "mailto:a@example.test",
            "/just/a/path", "http:///nohost"})
    void nonHttpInitialUrlIsInvalidAndNoRequestIsMade(String url) {
        FetchResult result = fetcher(5).fetch(URI.create(url));

        assertFailed(result, FetchFailureReason.INVALID_URL, null);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void failureAndSuccessResultsNeverExposeTheQueryString() {
        server.serve("/feed", 200, RSS, FeedStubServer.utf8("<rss/>"), Map.of());

        FetchResult result = fetcher(5).fetch(URI.create(server.baseUrl() + "/feed?token=s3cret"));

        assertThat(result.toString()).doesNotContain("s3cret").doesNotContain("<rss/>");
        assertThat(server.requests().get(0).query()).isEqualTo("token=s3cret");
    }
}
