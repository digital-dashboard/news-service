package com.j11a.argus.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Production runs Postgres 18. No reuse: each run gets a fresh database. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    private static final String IMAGE = "postgres:18-alpine";

    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGE));
    }

    // @ServiceConnection would configure spring.datasource.*, leaving the validated argus.db.* blank.
    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("argus.db.url", postgres::getJdbcUrl);
            registry.add("argus.db.username", postgres::getUsername);
            registry.add("argus.db.password", postgres::getPassword);
        };
    }
}
