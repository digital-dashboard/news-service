package com.j11a.argus.feed.fetch;

import static com.j11a.argus.feed.fetch.FetchAssertions.assertFailed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.feed.fetch.FetchResult.Failed;
import com.j11a.argus.testsupport.FeedStubServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/** Every failure says why: the error type and message that end up in the logs. */
class FeedFetcherErrorDetailTest {

    private static final FetchProperties.Retry RETRY =
            new FetchProperties.Retry(2, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(25));
    private static final DataSize MAX_BODY = DataSize.ofKilobytes(1);
    private static final int MAX_REDIRECTS = 1;
    private static final String RSS = "application/rss+xml";
    private static final String USER_AGENT = "Argus-Test/1.0";
    private static final String HTTP_STATUS = "HttpStatus";

    private FeedStubServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FeedStubServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private static FeedFetcher fetcher() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return new FeedFetcher(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient)),
                new FetchProperties(USER_AGENT, MAX_BODY, MAX_REDIRECTS, RETRY));
    }

    private URI url(String path) {
        return URI.create(server.baseUrl() + path);
    }

    @Test
    void aServerErrorNamesTheStatusAndItsReasonPhrase() {
        server.serve("/down", 503, "text/plain", FeedStubServer.utf8("busy"));

        Failed failed = assertFailed(fetcher().fetch(url("/down")), FetchFailureReason.HTTP_STATUS, 503);

        assertThat(failed.error()).isEqualTo(new FetchError(HTTP_STATUS, "503 Service Unavailable"));
    }

    @Test
    void aClientErrorNamesTheStatusAndItsReasonPhrase() {
        server.serve("/missing", 404, "text/plain", FeedStubServer.utf8("nope"));

        Failed failed = assertFailed(fetcher().fetch(url("/missing")), FetchFailureReason.HTTP_STATUS, 404);

        assertThat(failed.error()).isEqualTo(new FetchError(HTTP_STATUS, "404 Not Found"));
    }

    @Test
    void anUnregisteredStatusCodeIsNamedByItsNumber() {
        server.serve("/odd", 599, "text/plain", FeedStubServer.utf8("odd"));

        Failed failed = assertFailed(fetcher().fetch(url("/odd")), FetchFailureReason.HTTP_STATUS, 599);

        assertThat(failed.error()).isEqualTo(new FetchError(HTTP_STATUS, "599"));
    }

    @Test
    void aConnectionRefusedToAClosedPortIsAConnectException() throws IOException {
        URI refused;
        try (FeedStubServer closed = new FeedStubServer()) {
            refused = URI.create(closed.baseUrl() + "/feed");
        }

        Failed failed = assertFailed(fetcher().fetch(refused), FetchFailureReason.IO, null);

        assertThat(failed.error()).extracting(FetchError::type).isEqualTo("ConnectException");
    }

    @Test
    void aTooLargeBodyNamesTheLimit() {
        server.serve("/big", 200, RSS, new byte[(int) MAX_BODY.toBytes() + 1]);

        Failed failed = assertFailed(fetcher().fetch(url("/big")), FetchFailureReason.TOO_LARGE, null);

        assertThat(failed.error()).isEqualTo(new FetchError("BodyTooLarge", "body exceeded 1024 bytes"));
    }

    @Test
    void theRedirectLimitIsNamedWithItsValue() {
        server.redirect("/r0", 302, "/r1").redirect("/r1", 302, "/r2").redirect("/r2", 302, "/r3");

        Failed failed = assertFailed(fetcher().fetch(url("/r0")), FetchFailureReason.REDIRECT_LIMIT, 302);

        assertThat(failed.error()).isEqualTo(new FetchError("RedirectLimit", "more than 1 redirects"));
    }

    @Test
    void aRedirectWithoutALocationSaysSo() {
        server.serve("/bounce", 302, null, new byte[0]);

        Failed failed = assertFailed(fetcher().fetch(url("/bounce")), FetchFailureReason.HTTP_STATUS, 302);

        assertThat(failed.error())
                .isEqualTo(new FetchError(HTTP_STATUS, "302 redirect without a Location header"));
    }

    @Test
    void aRedirectToAnInvalidLocationIsAnInvalidUrl() {
        server.redirect("/bounce", 302, "http://[bad");

        Failed failed = assertFailed(fetcher().fetch(url("/bounce")), FetchFailureReason.INVALID_URL, null);

        assertThat(failed.error()).isEqualTo(new FetchError("InvalidUrl", "redirect Location is not a valid URL"));
    }

    @Test
    void aNonHttpSchemeIsAnInvalidUrlThatNamesTheScheme() {
        Failed failed = assertFailed(fetcher().fetch(URI.create("ftp://files.example.test/feed.xml")),
                FetchFailureReason.INVALID_URL, null);

        assertThat(failed.error()).isEqualTo(new FetchError("InvalidUrl", "unsupported scheme ftp"));
    }

    @Test
    void userInfoInTheUrlIsRejectedWithoutEchoingIt() {
        Failed failed = assertFailed(fetcher().fetch(URI.create("https://user:pw@feeds.example.test/rss.xml")),
                FetchFailureReason.INVALID_URL, null);

        assertThat(failed.error()).isEqualTo(new FetchError("InvalidUrl", "URL contains user-info"));
    }

    @Test
    void aTimeoutIsAnHttpTimeoutException() {
        server.stallBeforeHeaders("/silent", 5_000);
        FeedFetcher impatient = new FeedFetcher(
                RestClient.builder().requestFactory(timeoutFactory(Duration.ofMillis(200))),
                new FetchProperties(USER_AGENT, MAX_BODY, MAX_REDIRECTS, RETRY));

        Failed failed = assertFailed(impatient.fetch(url("/silent")), FetchFailureReason.TIMEOUT, null);

        assertThat(failed.error()).extracting(FetchError::type).isEqualTo("HttpTimeoutException");
    }

    private static JdkClientHttpRequestFactory timeoutFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @Test
    void urlsInsideAnIoFailureMessageAreRedacted() {
        FeedFetcher failing = new FeedFetcher(RestClient.builder().requestFactory((uri, method) ->
                new MockClientHttpRequest(method, uri) {
                    @Override
                    protected ClientHttpResponse executeInternal() throws IOException {
                        throw new IOException("reset while fetching https://feeds.example.test/rss?token=SECRET");
                    }
                }), new FetchProperties(USER_AGENT, MAX_BODY, MAX_REDIRECTS, RETRY));

        Failed failed = assertFailed(failing.fetch(URI.create("https://feeds.example.test/rss")),
                FetchFailureReason.IO, null);

        assertThat(failed.error()).isEqualTo(
                new FetchError("IOException", "reset while fetching https://feeds.example.test/rss"));
    }

    @Test
    void aRetryableFailureKeepsTheOriginalExceptionAsItsCause() throws IOException {
        URI refused;
        try (FeedStubServer closed = new FeedStubServer()) {
            refused = URI.create(closed.baseUrl() + "/feed");
        }

        assertThatThrownBy(() -> fetcher().fetchRetryable(refused, FetchValidators.EMPTY))
                .isInstanceOfSatisfying(RetryableFetchException.class, e -> {
                    assertThat(e.getCause()).isNotNull();
                    assertThat(e.toFailedResult().error()).extracting(FetchError::type)
                            .isEqualTo("ConnectException");
                });
    }

    @Test
    void aRetryableServerErrorCarriesTheStatusDetail() {
        server.serve("/down", 503, "text/plain", FeedStubServer.utf8("busy"));

        assertThatThrownBy(() -> fetcher().fetchRetryable(url("/down"), FetchValidators.EMPTY))
                .isInstanceOfSatisfying(RetryableFetchException.class, e ->
                        assertThat(e.toFailedResult())
                                .isEqualTo(new Failed(FetchFailureReason.HTTP_STATUS, 503,
                                        new FetchError(HTTP_STATUS, "503 Service Unavailable"))));
    }
}
