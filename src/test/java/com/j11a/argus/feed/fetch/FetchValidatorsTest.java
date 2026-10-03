package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FetchValidatorsTest {

    @Test
    void validatorsAreEmptyOnlyWhenNeitherHeaderIsPresent() {
        assertThat(FetchValidators.EMPTY.isEmpty()).isTrue();
        assertThat(new FetchValidators("\"v1\"", null).isEmpty()).isFalse();
        assertThat(new FetchValidators(null, "Wed, 21 Oct 2026 07:28:00 GMT").isEmpty()).isFalse();
        assertThat(new FetchValidators("\"v1\"", "Wed, 21 Oct 2026 07:28:00 GMT").isEmpty()).isFalse();
    }
}
