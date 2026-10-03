package com.j11a.argus.ingest.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkipReasonTest {

    @Test
    void tagsMatchContract() {
        assertThat(SkipReason.MISSING_IDENTITY.tag()).isEqualTo("missing_identity");
        assertThat(SkipReason.BATCH_DUPLICATE.tag()).isEqualTo("batch_duplicate");
    }
}
