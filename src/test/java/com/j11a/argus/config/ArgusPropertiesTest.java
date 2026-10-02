package com.j11a.argus.config;

import static com.j11a.argus.testsupport.StartupTestSupport.KEY_32;
import static com.j11a.argus.testsupport.StartupTestSupport.causeChain;
import static com.j11a.argus.testsupport.StartupTestSupport.withOverrides;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ArgusPropertiesTest {

    private static final String SHORT_KEY = "short-secret-key-0123456789";

    private static final List<String> DEFAULTS = List.of(
            "argus.db.url=jdbc:postgresql://localhost:5432/argus",
            "argus.db.username=argus",
            "argus.db.password=db-password-value",
            "argus.admin.key=" + KEY_32,
            "argus.telemetry.otlp-base-url=http://tempo:4318",
            "argus.telemetry.sampling-probability=1.0");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ArgusConfiguration.class);

    private static String[] valid(String... overrides) {
        return withOverrides(DEFAULTS, "", overrides).toArray(String[]::new);
    }

    private String captureFailure(String... overrides) {
        String[] holder = new String[1];
        runner.withPropertyValues(valid(overrides))
                .run(context -> holder[0] = causeChain(context.getStartupFailure()));
        return holder[0];
    }

    @Test
    void bindsValidConfiguration() {
        runner.withPropertyValues(valid()).run(context -> {
            assertThat(context).hasNotFailed();
            ArgusProperties properties = context.getBean(ArgusProperties.class);
            assertThat(properties.db().username()).isEqualTo("argus");
            assertThat(properties.admin().key()).isEqualTo(KEY_32);
            assertThat(properties.telemetry().otlpBaseUrl()).isEqualTo("http://tempo:4318");
            assertThat(properties.telemetry().samplingProbability()).isEqualTo(1.0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"argus.admin.key=", "argus.admin.key=   "})
    void failsWhenAdminKeyIsBlank(String override) {
        assertThat(captureFailure(override)).contains("argus.admin.key");
    }

    @Test
    void failsWhenAdminKeyIsShorterThan32AndNeverPrintsTheKey() {
        String failure = captureFailure("argus.admin.key=" + SHORT_KEY);

        assertThat(failure).contains("argus.admin.key must be at least 32 characters").doesNotContain(SHORT_KEY);
    }

    @Test
    void acceptsAdminKeyOfExactly32Characters() {
        runner.withPropertyValues(valid("argus.admin.key=" + KEY_32)).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "username", "password"})
    void failsWhenADatabaseSettingIsMissing(String setting) {
        assertThat(captureFailure("argus.db." + setting + "=")).contains("argus.db." + setting);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://tempo:4318/v1/traces", "tempo:4318", "ftp://tempo:4318", "http://tempo:4318/"})
    void failsWhenOtlpBaseUrlIsNotABaseUrl(String url) {
        assertThat(captureFailure("argus.telemetry.otlp-base-url=" + url))
                .contains("argus.telemetry.otlp-base-url");
    }

    @Test
    void failsWhenOtlpBaseUrlIsBlank() {
        assertThat(captureFailure("argus.telemetry.otlp-base-url=")).contains("argus.telemetry.otlp-base-url");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "1.1"})
    void failsWhenSamplingProbabilityIsOutOfRange(String probability) {
        assertThat(captureFailure("argus.telemetry.sampling-probability=" + probability))
                .contains("samplingProbability");
    }

    @Test
    void toStringMasksThePasswordAndTheKey() {
        runner.withPropertyValues(valid()).run(context -> {
            String text = context.getBean(ArgusProperties.class).toString();

            assertThat(text).doesNotContain("db-password-value").doesNotContain(KEY_32);
            assertThat(text).contains("***");
        });
    }
}
