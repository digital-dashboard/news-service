package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AbsoluteHttpUrlTest {

    private final AbsoluteHttpUrl.Validator validator = new AbsoluteHttpUrl.Validator();

    @Test
    void aNullValueIsLeftToNotBlank() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankValuesAreLeftToNotBlank(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.test/rss.xml", "http://example.test"})
    void absoluteHttpUrlsAreValid(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.test/rss", "/relative/path", "https://user:pw@example.test/rss"})
    void otherSchemesRelativeUrlsAndUserInfoAreInvalid(String value) {
        assertThat(validator.isValid(value, null)).isFalse();
    }
}
