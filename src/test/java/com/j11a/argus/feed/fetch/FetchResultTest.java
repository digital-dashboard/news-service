package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.fetch.FetchResult.Fetched;
import java.net.URI;
import org.junit.jupiter.api.Test;

class FetchResultTest {

    private static final URI URL = URI.create("https://example.test/feed");

    @Test
    void fetchedComparesBodyContentNotArrayIdentity() {
        Fetched first = new Fetched(new byte[] {1, 2}, "text/xml", URL, null);
        Fetched same = new Fetched(new byte[] {1, 2}, "text/xml", URL, null);
        Fetched other = new Fetched(new byte[] {1, 3}, "text/xml", URL, null);

        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
    }

    @Test
    void fetchedDiffersWhenThePermanentTargetDiffers() {
        assertThat(new Fetched(new byte[0], null, URL, URL)).isNotEqualTo(new Fetched(new byte[0], null, URL, null));
    }

    @Test
    void failureReasonsExposeLowercaseTags() {
        assertThat(FetchFailureReason.HTTP_STATUS.tag()).isEqualTo("http_status");
        assertThat(FetchFailureReason.TIMEOUT.tag()).isEqualTo("timeout");
        assertThat(FetchFailureReason.REDIRECT_LIMIT.tag()).isEqualTo("redirect_limit");
    }
}
