package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.fetch.FetchResult.Failed;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/** Faults a loopback server cannot produce deterministically, injected through the request factory. */
class FeedFetcherFaultTest {

    private static final URI FEED = URI.create("https://feeds.example.test/rss.xml");

    private static FetchResult fetchThrough(ClientHttpRequestFactory factory) {
        FeedFetcher fetcher = new FeedFetcher(RestClient.builder().requestFactory(factory),
                new FetchProperties("Argus-Test/1.0", DataSize.ofKilobytes(1), 5));
        return fetcher.fetch(FEED);
    }

    private static ClientHttpRequestFactory failingToSend(IOException failure) {
        return (uri, method) -> new MockClientHttpRequest(method, uri) {
            @Override
            protected ClientHttpResponse executeInternal() throws IOException {
                throw failure;
            }
        };
    }

    private static ClientHttpRequestFactory bodyFailingWith(String message) {
        InputStream body = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException(message);
            }
        };
        return (uri, method) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            request.setResponse(new MockClientHttpResponse(body, HttpStatus.OK));
            return request;
        };
    }

    @Test
    void aBodyStreamClosedMidReadIsATimeout() {
        FetchResult result = fetchThrough(bodyFailingWith("closed"));

        assertThat(result).isEqualTo(new Failed(FetchFailureReason.TIMEOUT, null));
    }

    @Test
    void aBodyReadFailingForAnyOtherReasonIsAnIoFailure() {
        FetchResult result = fetchThrough(bodyFailingWith("Connection reset"));

        assertThat(result).isEqualTo(new Failed(FetchFailureReason.IO, null));
    }

    @Test
    void aSocketTimeoutAnywhereInTheCauseChainIsATimeout() {
        IOException failure = new IOException("send failed", new SocketTimeoutException("Read timed out"));

        FetchResult result = fetchThrough(failingToSend(failure));

        assertThat(result).isEqualTo(new Failed(FetchFailureReason.TIMEOUT, null));
    }

    @Test
    void anInterruptedRequestIsAnIoFailureAndKeepsTheInterruptFlag() {
        IOException failure = new IOException("Request was interrupted", new InterruptedException());

        FetchResult result = fetchThrough(failingToSend(failure));

        assertThat(result).isEqualTo(new Failed(FetchFailureReason.IO, null));
        assertThat(Thread.interrupted()).isTrue();
    }
}
