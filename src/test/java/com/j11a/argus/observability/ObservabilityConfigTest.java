package com.j11a.argus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
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

    @Test
    void clientUrlsLoseTheirQueryStringAndUserInfo() {
        ObservationFilter filter = new ObservabilityConfig().clientUrlsLoseTheirQueryString();
        org.springframework.http.client.observation.ClientRequestObservationContext context =
                new org.springframework.http.client.observation.ClientRequestObservationContext(
                        new org.springframework.mock.http.client.MockClientHttpRequest(
                                org.springframework.http.HttpMethod.GET,
                                java.net.URI.create("https://user:pw@feeds.example.test:8443/a/b.xml?token=hunter2")));

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValue("http.url").getValue())
                .isEqualTo("https://feeds.example.test:8443/a/b.xml");
    }

    @Test
    void otherObservationsPassThroughTheUrlFilterUntouched() {
        ObservationFilter filter = new ObservabilityConfig().clientUrlsLoseTheirQueryString();
        Observation.Context context = new Observation.Context();

        assertThat(filter.map(context)).isSameAs(context);
    }
}
