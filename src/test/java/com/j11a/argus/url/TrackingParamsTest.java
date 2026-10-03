package com.j11a.argus.url;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrackingParamsTest {

    @ParameterizedTest
    @ValueSource(strings = {"fbclid", "gclid", "mc_cid", "mc_eid", "cmpid", "ref"})
    void exactTrackingParamsAreRecognized(String name) {
        assertThat(TrackingParams.isTracking(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"utm_source", "utm_medium", "utm_campaign", "at_medium", "at_campaign", "at_custom1"})
    void prefixTrackingParamsAreRecognized(String name) {
        assertThat(TrackingParams.isTracking(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"p", "id", "article", "ref_other", "utm", "at", "outputType", "format"})
    void nonTrackingParamsAreNotRecognized(String name) {
        assertThat(TrackingParams.isTracking(name)).isFalse();
    }

    @Test
    void emptyOrNullAreNotTracking() {
        assertThat(TrackingParams.isTracking("")).isFalse();
    }
}
