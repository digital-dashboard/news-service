package com.j11a.argus.integration;

import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class OtlpStubConfig {

    @Bean
    OtlpStubServer otlpStubServer() throws IOException {
        return new OtlpStubServer();
    }

    @Bean
    DynamicPropertyRegistrar telemetryProperties(OtlpStubServer otlpStubServer) {
        return registry -> registry.add("argus.telemetry.otlp-base-url", otlpStubServer::baseUrl);
    }
}
