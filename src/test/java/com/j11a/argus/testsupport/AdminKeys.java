package com.j11a.argus.testsupport;

import com.j11a.argus.config.ArgusProperties;
import com.j11a.argus.security.AdminKeyFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Dummy admin key. It is the value application-it.yml gives the integration tests. */
public final class AdminKeys {

    public static final String HEADER = AdminKeyFilter.HEADER;
    public static final String VALID = "integration-test-admin-key-0123456789abcdef";

    private AdminKeys() {
    }

    /** Gives a @WebMvcTest slice the properties SecurityConfig needs, without a full property binding. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class SliceProperties {

        @Bean
        ArgusProperties argusProperties() {
            return new ArgusProperties(
                    new ArgusProperties.Db("jdbc:postgresql://unused/unused", "unused", "unused"),
                    new ArgusProperties.Admin(VALID),
                    new ArgusProperties.Telemetry("http://localhost:4318", 1.0));
        }
    }
}
