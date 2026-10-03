package com.j11a.argus.feed.fetch;

import com.j11a.argus.feed.fetch.FetchResult.Failed;
import com.j11a.argus.feed.fetch.FetchResult.Fetched;
import com.j11a.argus.feed.fetch.FetchResult.NotModified;
import com.j11a.argus.url.HttpUrls;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Downloads a feed. Redirects are followed here, not by the HTTP client, so every hop is validated.
 * Query strings can carry tokens, so they are never logged or returned in failures.
 */
@Component
public class FeedFetcher {

    static final String ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, "
            + "text/xml;q=0.9, */*;q=0.8";
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<Integer> PERMANENT_REDIRECT_STATUSES = Set.of(301, 308);
    private static final String HTTP_STATUS_ERROR = "HttpStatus";
    private static final String INVALID_URL = "InvalidUrl";
    private static final long MAX_READABLE_BYTES = Integer.MAX_VALUE - 16L;

    private sealed interface Step {
    }

    private record Redirect(int status, @Nullable String location) implements Step {
    }

    private record Rejected(Failed failure) implements Step {
    }

    private record NotModifiedStep(FetchValidators validators) implements Step {
    }

    private static final class Body implements Step {

        private final byte[] bytes;
        private final @Nullable String contentType;
        private final FetchValidators validators;

        private Body(byte[] bytes, @Nullable String contentType, FetchValidators validators) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.validators = validators;
        }
    }

    private sealed interface Hop {
    }

    private record Move(URI next, boolean permanent) implements Hop {
    }

    private record Stop(Failed failure) implements Hop {
    }

    private final RestClient client;
    private final FetchProperties properties;

    public FeedFetcher(RestClient.Builder builder, FetchProperties properties) {
        this.client = builder.build();
        this.properties = properties;
    }

    public FetchResult fetch(URI url) {
        return fetch(url, FetchValidators.EMPTY);
    }

    public FetchResult fetch(URI url, FetchValidators validators) {
        return doFetch(url, validators, false);
    }

    /** Throws RetryableFetchException on 5xx and transient I/O, for a RetryTemplate to catch. */
    public FetchResult fetchRetryable(URI url, FetchValidators validators) {
        return doFetch(url, validators, true);
    }

    private FetchResult doFetch(URI url, FetchValidators validators, boolean throwOnTransient) {
        URI current = url;
        URI permanentTarget = null;
        boolean permanentChain = true;
        for (int redirects = 0; ; redirects++) {
            Step step = attempt(current, validators, throwOnTransient);
            if (step instanceof Rejected(var rejection)) {
                return rejection;
            }
            if (step instanceof NotModifiedStep(var notModifiedValidators)) {
                return new NotModified(current, permanentTarget, notModifiedValidators);
            }
            if (step instanceof Body body) {
                return new Fetched(body.bytes, body.contentType, current, permanentTarget, body.validators);
            }
            Hop hop = nextHop(current, (Redirect) step, redirects);
            if (hop instanceof Stop(var stopped)) {
                return stopped;
            }
            Move move = (Move) hop;
            permanentChain &= move.permanent();
            if (permanentChain) {
                permanentTarget = move.next();
            }
            current = move.next();
        }
    }

    private Step attempt(URI current, FetchValidators validators, boolean throwOnTransient) {
        if (!HttpUrls.isHttp(current) || HttpUrls.hasUserInfo(current)) {
            return new Rejected(new Failed(FetchFailureReason.INVALID_URL, null,
                    new FetchError(INVALID_URL, invalidUrlDetail(current))));
        }
        try {
            return request(current, validators, throwOnTransient);
        } catch (RestClientException e) {
            // classify only ever answers TIMEOUT or IO, and both are transient.
            FetchFailureReason reason = classify(e);
            if (throwOnTransient) {
                throw new RetryableFetchException(reason, e);
            }
            return new Rejected(new Failed(reason, null, FetchError.of(e)));
        }
    }

    private Hop nextHop(URI current, Redirect redirect, int redirects) {
        if (redirect.location() == null) {
            return new Stop(new Failed(FetchFailureReason.HTTP_STATUS, redirect.status(),
                    new FetchError(HTTP_STATUS_ERROR, redirect.status() + " redirect without a Location header")));
        }
        if (redirects >= properties.maxRedirects()) {
            return new Stop(new Failed(FetchFailureReason.REDIRECT_LIMIT, redirect.status(),
                    new FetchError("RedirectLimit", "more than " + properties.maxRedirects() + " redirects")));
        }
        return HttpUrls.resolve(current, redirect.location())
                .<Hop>map(next -> new Move(next, PERMANENT_REDIRECT_STATUSES.contains(redirect.status())))
                .orElseGet(() -> new Stop(new Failed(FetchFailureReason.INVALID_URL, null,
                        new FetchError(INVALID_URL, "redirect Location is not a valid URL"))));
    }

    private Step request(URI url, FetchValidators validators, boolean throwOnTransient) {
        return client.get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, properties.userAgent())
                .header(HttpHeaders.ACCEPT, ACCEPT)
                .headers(headers -> applyValidators(headers, validators))
                .exchange((request, response) -> handle(response, validators, throwOnTransient), true);
    }

    private static void applyValidators(HttpHeaders headers, FetchValidators validators) {
        if (validators.etag() != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, validators.etag());
        }
        if (validators.lastModified() != null) {
            headers.set(HttpHeaders.IF_MODIFIED_SINCE, validators.lastModified());
        }
    }

    private Step handle(ClientHttpResponse response, FetchValidators sent, boolean throwOnTransient)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        int code = status.value();
        if (code == 304) {
            if (sent.isEmpty()) {
                return new Rejected(httpFailure(code));
            }
            String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
            String lastModified = response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
            return new NotModifiedStep(new FetchValidators(etag, lastModified));
        }
        if (REDIRECT_STATUSES.contains(code)) {
            return new Redirect(code, response.getHeaders().getFirst(HttpHeaders.LOCATION));
        }
        if (status.is5xxServerError()) {
            if (throwOnTransient) {
                throw new RetryableFetchException(FetchFailureReason.HTTP_STATUS, code, httpError(code));
            }
            return new Rejected(httpFailure(code));
        }
        if (!status.is2xxSuccessful()) {
            return new Rejected(httpFailure(code));
        }
        long maxBytes = Math.min(properties.maxBodySize().toBytes(), MAX_READABLE_BYTES);
        if (response.getHeaders().getContentLength() > maxBytes) {
            return new Rejected(tooLarge(maxBytes));
        }
        byte[] body = readCapped(response, (int) maxBytes + 1);
        if (body.length > maxBytes) {
            return new Rejected(tooLarge(maxBytes));
        }
        String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
        String lastModified = response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
        return new Body(body, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                new FetchValidators(etag, lastModified));
    }

    private static Failed tooLarge(long maxBytes) {
        return new Failed(FetchFailureReason.TOO_LARGE, null,
                new FetchError("BodyTooLarge", "body exceeded " + maxBytes + " bytes"));
    }

    private static FetchError httpError(int code) {
        HttpStatus known = HttpStatus.resolve(code);
        return new FetchError(HTTP_STATUS_ERROR, known == null ? String.valueOf(code) : code + " " + known.getReasonPhrase());
    }

    private static Failed httpFailure(int code) {
        return new Failed(FetchFailureReason.HTTP_STATUS, code, httpError(code));
    }

    private static String invalidUrlDetail(URI uri) {
        return HttpUrls.isHttp(uri) ? "URL contains user-info" : "unsupported scheme " + uri.getScheme();
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
