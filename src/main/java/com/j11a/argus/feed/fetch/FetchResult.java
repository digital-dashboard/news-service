package com.j11a.argus.feed.fetch;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public sealed interface FetchResult {

    record Fetched(byte[] body, @Nullable String contentType, URI finalUrl, boolean permanentRedirect)
            implements FetchResult {

        @Override
        public boolean equals(Object other) {
            return other instanceof Fetched that
                    && Arrays.equals(body, that.body)
                    && Objects.equals(contentType, that.contentType)
                    && finalUrl.equals(that.finalUrl)
                    && permanentRedirect == that.permanentRedirect;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(body), contentType, finalUrl, permanentRedirect);
        }

        // Neither the body nor the query string belongs in a log line.
        @Override
        public String toString() {
            return "Fetched[bodyBytes=" + body.length + ", contentType=" + contentType
                    + ", permanentRedirect=" + permanentRedirect + "]";
        }
    }

    record Failed(FetchFailureReason reason, @Nullable Integer httpStatus) implements FetchResult {
    }
}
