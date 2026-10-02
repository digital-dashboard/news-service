package com.j11a.argus.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    static final String ACTUATOR_PREFIX = "/actuator";

    /** Actuator requests get no span and no http.server.requests metric, so they don't flood Tempo or skew latency. */
    @Bean
    ObservationPredicate actuatorRequestsAreNotObserved() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getRequestURI().startsWith(ACTUATOR_PREFIX));
    }
}
