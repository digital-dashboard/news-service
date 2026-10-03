package com.j11a.argus.feed.fetch;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public sealed interface FetchResult {

    /**
     * permanentTarget is the last URL reached through an unbroken chain of 301/308 hops from the first request; null
     * when the first hop was not permanent or there were no redirects.
     */
    record Fetched(byte[] body, @Nullable String contentType, URI finalUrl, @Nullable URI permanentTarget,
                   FetchValidators validators)
            implements FetchResult {

        public Fetched(byte[] body, @Nullable String contentType, URI finalUrl, @Nullable URI permanentTarget) {
            this(body, contentType, finalUrl, permanentTarget, FetchValidators.EMPTY);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Fetched(var otherBody, var otherType, var otherUrl, var otherTarget, var otherVal)
                    && Arrays.equals(body, otherBody)
                    && Objects.equals(contentType, otherType)
                    && finalUrl.equals(otherUrl)
                    && Objects.equals(permanentTarget, otherTarget)
                    && Objects.equals(validators, otherVal);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(body), contentType, finalUrl, permanentTarget, validators);
        }

        // Neither the body nor the query string belongs in a log line.
        @Override
        public String toString() {
            return "Fetched[bodyBytes=" + body.length + ", contentType=" + contentType
                    + ", permanentTarget=" + (permanentTarget != null) + "]";
        }
    }

    record NotModified(URI finalUrl, @Nullable URI permanentTarget, FetchValidators validators) implements FetchResult {

        public NotModified(URI finalUrl, @Nullable URI permanentTarget) {
            this(finalUrl, permanentTarget, FetchValidators.EMPTY);
        }
    }

    record Failed(FetchFailureReason reason, @Nullable Integer httpStatus) implements FetchResult {
    }
}
