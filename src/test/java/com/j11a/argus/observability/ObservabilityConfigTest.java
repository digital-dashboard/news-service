package com.j11a.argus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ObservabilityConfigTest {

    private static final String OBSERVATION_NAME = "http.server.requests";

    private final ObservationPredicate predicate = new ObservabilityConfig().actuatorRequestsAreNotObserved();

    private static ServerRequestObservationContext contextFor(String uri) {
        return new ServerRequestObservationContext(
                new MockHttpServletRequest("GET", uri), new MockHttpServletResponse());
    }

    @Test
    void dropsActuatorRequests() {
        assertThat(predicate.test(OBSERVATION_NAME, contextFor("/actuator/health"))).isFalse();
        assertThat(predicate.test(OBSERVATION_NAME, contextFor("/actuator/prometheus"))).isFalse();
    }

    @Test
    void keepsApiRequests() {
        assertThat(predicate.test(OBSERVATION_NAME, contextFor("/news/v2/x"))).isTrue();
    }

    @Test
    void keepsObservationsThatAreNotServerRequests() {
        assertThat(predicate.test("argus.fetch", new Observation.Context())).isTrue();
    }
}
