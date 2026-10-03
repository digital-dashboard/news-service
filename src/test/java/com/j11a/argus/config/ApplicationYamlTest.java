package com.j11a.argus.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class ApplicationYamlTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    private void withEnvironment(Consumer<Environment> assertions, String... properties) {
        runner.withPropertyValues(properties).run(context -> assertions.accept(context.getEnvironment()));
    }

    @Test
    void applicationIsNamedArgus() {
        withEnvironment(env -> assertThat(env.getProperty("spring.application.name")).isEqualTo("argus"));
    }

    @Test
    void defaultOtlpEndpointAppendsTheTracesPathToTheDefaultBaseUrl() {
        withEnvironment(env -> assertThat(env.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://tempo:4318/v1/traces"));
    }

    @Test
    void overriddenBaseUrlIsHonouredAndGetsTheTracesPathAppended() {
        withEnvironment(env -> assertThat(env.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://localhost:9999/v1/traces"), "argus.telemetry.otlp-base-url=http://localhost:9999");
    }

    @Test
    void environmentVariableMappingOfTheOtlpEndpointIsDisabled() {
        withEnvironment(env -> assertThat(env.getProperty("management.opentelemetry.map-environment-variables"))
                .isEqualTo("false"));
    }

    @Test
    void secretFilePropertyIsTheFallbackForTheAdminKey() {
        withEnvironment(env -> assertThat(env.getProperty("argus.admin.key")).isEqualTo("from-secret-file"),
                "argus_admin_key=from-secret-file");
    }

    @Test
    void secretFilePropertiesAreTheFallbackForTheDatabaseCredentials() {
        withEnvironment(env -> {
            assertThat(env.getProperty("argus.db.username")).isEqualTo("secret-user");
            assertThat(env.getProperty("argus.db.password")).isEqualTo("secret-password");
        }, "argus_db_username=secret-user", "argus_db_password=secret-password");
    }

    @Test
    void livenessChecksTheApplicationOnlyAndReadinessAlsoChecksTheDatabase() {
        withEnvironment(env -> {
            assertThat(env.getProperty("management.endpoint.health.group.liveness.include"))
                    .isEqualTo("livenessState");
            assertThat(env.getProperty("management.endpoint.health.group.readiness.include"))
                    .isEqualTo("readinessState,db");
            assertThat(env.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
            assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        });
    }

    @Test
    void onlyHealthAndPrometheusAreExposed() {
        withEnvironment(env -> assertThat(env.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus"));
    }

    @Test
    void percentileHistogramsAreEnabledForTheDashboardTimers() {
        String prefix = "management.metrics.distribution.percentiles-histogram.";
        withEnvironment(env -> {
            assertThat(env.getProperty(prefix + "http.server.requests")).isEqualTo("true");
            assertThat(env.getProperty(prefix + "http.client.requests")).isEqualTo("true");
            assertThat(env.getProperty(prefix + "hikaricp.connections.acquire")).isEqualTo("true");
            assertThat(env.getProperty(prefix + "argus")).isEqualTo("true");
        });
    }

    @Test
    void springSecurityObservationsAreDisabledSoActuatorRequestsLeaveNoOrphanSpans() {
        withEnvironment(env -> assertThat(env.getProperty("management.observations.enable.spring.security"))
                .isEqualTo("false"));
    }

    @Test
    void metricsAreTaggedWithTheApplicationName() {
        withEnvironment(env -> assertThat(env.getProperty("management.metrics.tags.application"))
                .isEqualTo("argus"));
    }

    @Test
    void otlpMetricsAndLogExportAreOff() {
        withEnvironment(env -> {
            assertThat(env.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
            assertThat(env.getProperty("management.logging.export.otlp.enabled")).isEqualTo("false");
        });
    }

    @Test
    void utcIsSetThroughHibernateAndNotThroughConnectionInitSql() {
        withEnvironment(env -> {
            assertThat(env.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
            assertThat(env.getProperty("spring.datasource.hikari.connection-init-sql")).isNull();
        });
    }

    @Test
    void deployedDefaultsAreJsonLogsGracefulShutdownAndSafeJpa() {
        withEnvironment(env -> {
            assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("logstash");
            assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
            assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
            assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
            assertThat(env.getProperty("spring.datasource.hikari.pool-name")).isEqualTo("argus-pool");
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
            assertThat(env.getProperty("spring.web.resources.add-mappings")).isEqualTo("false");
        });
    }

    @Test
    void feedFetchDefaultsAreSetAndRedirectsAreLeftToTheFetcher() {
        withEnvironment(env -> {
            assertThat(env.getProperty("spring.http.clients.connect-timeout")).isEqualTo("5s");
            assertThat(env.getProperty("spring.http.clients.read-timeout")).isEqualTo("15s");
            assertThat(env.getProperty("spring.http.clients.redirects")).isEqualTo("dont-follow");
            assertThat(env.getProperty("argus.fetch.user-agent")).isEqualTo("Argus/0.1 (self-hosted RSS reader)");
            assertThat(env.getProperty("argus.fetch.max-body-size")).isEqualTo("5MB");
            assertThat(env.getProperty("argus.fetch.max-redirects")).isEqualTo("5");
        });
    }

    @Test
    void feedFetchSettingsCanBeOverriddenThroughEnvironmentVariablePlaceholders() {
        withEnvironment(env -> {
            assertThat(env.getProperty("spring.http.clients.read-timeout")).isEqualTo("2s");
            assertThat(env.getProperty("argus.fetch.user-agent")).isEqualTo("Custom/9");
            assertThat(env.getProperty("argus.fetch.max-body-size")).isEqualTo("1MB");
            assertThat(env.getProperty("argus.fetch.max-redirects")).isEqualTo("2");
        }, "ARGUS_FETCH_READ_TIMEOUT=2s", "ARGUS_FETCH_USER_AGENT=Custom/9", "ARGUS_FETCH_MAX_BODY_SIZE=1MB",
                "ARGUS_FETCH_MAX_REDIRECTS=2");
    }

    @Test
    void devProfileUsesPlainLogsAndDisablesTraceExport() {
        withEnvironment(env -> {
            assertThat(env.getProperty("logging.structured.format.console")).isEmpty();
            assertThat(env.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
            assertThat(env.getProperty("argus.db.url")).isEqualTo("jdbc:postgresql://localhost:5432/argus");
        }, "spring.profiles.active=dev");
    }

    @Test
    void itProfileEnablesLiquibaseTestContext() {
        withEnvironment(env -> assertThat(env.getProperty("spring.liquibase.contexts")).isEqualTo("test"),
                "spring.profiles.active=it");
    }

    @Test
    void defaultProfileHasNoLiquibaseContexts() {
        withEnvironment(env -> assertThat(env.getProperty("spring.liquibase.contexts")).isNull());
    }
}
