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
    record Fetched(byte[] body, @Nullable String contentType, URI finalUrl, @Nullable URI permanentTarget)
            implements FetchResult {

        @Override
        public boolean equals(Object other) {
            return other instanceof Fetched that
                    && Arrays.equals(body, that.body)
                    && Objects.equals(contentType, that.contentType)
                    && finalUrl.equals(that.finalUrl)
                    && Objects.equals(permanentTarget, that.permanentTarget);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(body), contentType, finalUrl, permanentTarget);
        }

        // Neither the body nor the query string belongs in a log line.
        @Override
        public String toString() {
            return "Fetched[bodyBytes=" + body.length + ", contentType=" + contentType
                    + ", permanentTarget=" + (permanentTarget != null) + "]";
        }
    }

    record Failed(FetchFailureReason reason, @Nullable Integer httpStatus) implements FetchResult {
    }
}
