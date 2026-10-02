package com.j11a.argus.feed.fetch;

import com.j11a.argus.feed.fetch.FetchResult.Failed;
import com.j11a.argus.feed.fetch.FetchResult.Fetched;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Downloads a feed. Redirects are followed here, not by the HTTP client, so every hop is validated and reported.
 * Query strings can carry tokens, so they are never logged or returned in failures.
 */
@Component
public class FeedFetcher {

    static final String ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, "
            + "text/xml;q=0.9, */*;q=0.8";
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<Integer> PERMANENT_REDIRECT_STATUSES = Set.of(301, 308);
    private static final long MAX_READABLE_BYTES = Integer.MAX_VALUE - 16L;

    private sealed interface Step {
    }

    private record Redirect(int status, @Nullable String location) implements Step {
    }

    private record Rejected(Failed failure) implements Step {
    }

    private record Body(byte[] bytes, @Nullable String contentType) implements Step {
    }

    private final RestClient client;
    private final FetchProperties properties;

    public FeedFetcher(RestClient.Builder builder, FetchProperties properties) {
        this.client = builder.build();
        this.properties = properties;
    }

    public FetchResult fetch(URI url) {
        URI current = url;
        boolean permanentRedirect = false;
        for (int redirects = 0; ; redirects++) {
            if (!isHttp(current)) {
                return new Failed(FetchFailureReason.INVALID_URL, null);
            }
            Step step;
            try {
                step = request(current);
            } catch (RestClientException e) {
                return new Failed(classify(e), null);
            }
            if (step instanceof Rejected rejected) {
                return rejected.failure();
            }
            if (step instanceof Body body) {
                return new Fetched(body.bytes(), body.contentType(), current, permanentRedirect);
            }
            Redirect redirect = (Redirect) step;
            if (redirect.location() == null) {
                return new Failed(FetchFailureReason.HTTP_STATUS, redirect.status());
            }
            if (redirects >= properties.maxRedirects()) {
                return new Failed(FetchFailureReason.REDIRECT_LIMIT, redirect.status());
            }
            permanentRedirect |= PERMANENT_REDIRECT_STATUSES.contains(redirect.status());
            URI next = resolve(current, redirect.location());
            if (next == null) {
                return new Failed(FetchFailureReason.INVALID_URL, null);
            }
            current = next;
        }
    }

    private Step request(URI url) {
        return client.get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, properties.userAgent())
                .header(HttpHeaders.ACCEPT, ACCEPT)
                .exchange((request, response) -> handle(response), true);
    }

    private Step handle(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        int code = status.value();
        if (REDIRECT_STATUSES.contains(code)) {
            return new Redirect(code, response.getHeaders().getFirst(HttpHeaders.LOCATION));
        }
        if (!status.is2xxSuccessful()) {
            return new Rejected(new Failed(FetchFailureReason.HTTP_STATUS, code));
        }
        long maxBytes = Math.min(properties.maxBodySize().toBytes(), MAX_READABLE_BYTES);
        if (response.getHeaders().getContentLength() > maxBytes) {
            return new Rejected(new Failed(FetchFailureReason.TOO_LARGE, null));
        }
        byte[] body = readCapped(response, (int) maxBytes + 1);
        if (body.length > maxBytes) {
            return new Rejected(new Failed(FetchFailureReason.TOO_LARGE, null));
        }
        return new Body(body, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    }

    private static byte[] readCapped(ClientHttpResponse response, int limit) throws IOException {
        try (InputStream in = response.getBody()) {
            return in.readNBytes(limit);
        } catch (IOException e) {
            // Spring's JDK request factory enforces the read timeout by closing the body stream mid-read; the JDK then
            // reports a bare "closed". We have not closed the stream ourselves, so that can only be the timeout.
            if ("closed".equals(e.getMessage())) {
                throw new HttpTimeoutException("read timeout");
            }
            throw e;
        }
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        boolean httpScheme = scheme != null && ("http".equals(scheme.toLowerCase(Locale.ROOT))
                || "https".equals(scheme.toLowerCase(Locale.ROOT)));
        return httpScheme && uri.getHost() != null;
    }

    private static @Nullable URI resolve(URI current, String location) {
        try {
            return current.resolve(new URI(location.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static FetchFailureReason classify(RestClientException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return FetchFailureReason.TIMEOUT;
            }
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return FetchFailureReason.IO;
    }
}
