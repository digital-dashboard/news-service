package com.j11a.argus.ingest.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UpdateReasonTest {

    @Test
    void metricTagsAreTheDocumentedReasonValues() {
        assertThat(UpdateReason.CONTENT_CHANGED.tag()).isEqualTo("content_changed");
        assertThat(UpdateReason.TIMESTAMP_ONLY.tag()).isEqualTo("timestamp_only");
        assertThat(UpdateReason.INSERT_CONFLICT.tag()).isEqualTo("insert_conflict");
    }
}
