package com.j11a.argus.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationPredicate;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    static final String ACTUATOR_PREFIX = "/actuator";
    private static final String HTTP_URL = "http.url";

    /** Actuator requests get no span and no http.server.requests metric, so they don't flood Tempo or skew latency. */
    @Bean
    ObservationPredicate actuatorRequestsAreNotObserved() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getRequestURI().startsWith(ACTUATOR_PREFIX));
    }

    /** Feed URLs can carry tokens; the client span's http.url keeps scheme, host and path only. */
    @Bean
    ObservationFilter clientUrlsLoseTheirQueryString() {
        return context -> {
            if (context instanceof ClientRequestObservationContext client && client.getCarrier() != null) {
                client.addHighCardinalityKeyValue(KeyValue.of(HTTP_URL, withoutQuery(client.getCarrier().getURI())));
            }
            return context;
        };
    }

    static String withoutQuery(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getRawPath(), null, null)
                    .toASCIIString();
        } catch (URISyntaxException e) {
            return uri.getScheme() + "://" + uri.getHost();
        }
    }
}
