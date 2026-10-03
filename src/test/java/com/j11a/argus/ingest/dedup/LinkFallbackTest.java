package com.j11a.argus.ingest.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LinkFallbackTest {

    @Test
    void metricTagsAreTheDocumentedOutcomeValues() {
        assertThat(LinkFallback.GUID_REPLACED.tag()).isEqualTo("guid_replaced");
        assertThat(LinkFallback.GUARDED_HOMEPAGE.tag()).isEqualTo("guarded_homepage");
        assertThat(LinkFallback.GUARDED_SHARED.tag()).isEqualTo("guarded_shared");
    }
}
