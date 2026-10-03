package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class FetchPropertiesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static FetchProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("argus.fetch", FetchProperties.class).get();
    }

    @Test
    void bindsConfiguredValuesAndAppliesDefaults() {
        FetchProperties properties = bind(Map.of("argus.fetch.user-agent", "Argus/1"));

        assertThat(properties.userAgent()).isEqualTo("Argus/1");
        assertThat(properties.maxBodySize()).isEqualTo(DataSize.ofMegabytes(5));
        assertThat(properties.maxRedirects()).isEqualTo(5);
        assertThat(properties.retry().maxRetries()).isEqualTo(2);
        assertThat(properties.retry().delay()).isEqualTo(java.time.Duration.ofSeconds(1));
        assertThat(properties.retry().multiplier()).isEqualTo(2.0);
        assertThat(properties.retry().timeout()).isEqualTo(java.time.Duration.ofSeconds(25));
    }

    @Test
    void bindsCustomRetryProperties() {
        FetchProperties properties = bind(Map.of(
                "argus.fetch.user-agent", "Argus/1",
                "argus.fetch.retry.max-retries", "4",
                "argus.fetch.retry.delay", "500ms",
                "argus.fetch.retry.multiplier", "1.5",
                "argus.fetch.retry.timeout", "10s"));

        assertThat(properties.retry().maxRetries()).isEqualTo(4);
        assertThat(properties.retry().delay()).isEqualTo(java.time.Duration.ofMillis(500));
        assertThat(properties.retry().multiplier()).isEqualTo(1.5);
        assertThat(properties.retry().timeout()).isEqualTo(java.time.Duration.ofSeconds(10));
    }

    @Test
    void acceptsValidProperties() {
        assertThat(VALIDATOR.validate(new FetchProperties("Argus/1", DataSize.ofMegabytes(1), 10, FetchProperties.DEFAULT_RETRY))).isEmpty();
        assertThat(VALIDATOR.validate(new FetchProperties("Argus/1", DataSize.ofBytes(1), 0, FetchProperties.DEFAULT_RETRY))).isEmpty();
    }

    @Test
    void aMissingBodySizeFailsOnlyTheNotNullConstraint() {
        assertThat(VALIDATOR.validate(new FetchProperties("a", null, 5, FetchProperties.DEFAULT_RETRY)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getPropertyPath()).hasToString("maxBodySize"));
    }

    @Test
    void aNegativeBodySizeIsRejectedWithTheBodySizeMessage() {
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofBytes(-1), 5, FetchProperties.DEFAULT_RETRY)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .isEqualTo("argus.fetch.max-body-size must be positive"));
    }

    @Test
    void rejectsBlankUserAgentNonPositiveSizeAndOutOfRangeRedirects() {
        assertThat(VALIDATOR.validate(new FetchProperties(" ", DataSize.ofMegabytes(1), 5, FetchProperties.DEFAULT_RETRY))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofBytes(0), 5, FetchProperties.DEFAULT_RETRY))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), -1, FetchProperties.DEFAULT_RETRY))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), 11, FetchProperties.DEFAULT_RETRY))).hasSize(1);
    }

    @Test
    void rejectsInvalidRetryProperties() {
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), 5,
                new FetchProperties.Retry(-1, java.time.Duration.ofSeconds(1), 2.0, java.time.Duration.ofSeconds(25))))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), 5,
                new FetchProperties.Retry(11, java.time.Duration.ofSeconds(1), 2.0, java.time.Duration.ofSeconds(25))))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), 5,
                new FetchProperties.Retry(2, null, 2.0, java.time.Duration.ofSeconds(25))))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), 5,
                new FetchProperties.Retry(2, java.time.Duration.ofSeconds(1), 0.5, java.time.Duration.ofSeconds(25))))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), 5,
                new FetchProperties.Retry(2, java.time.Duration.ofSeconds(1), 2.0, null)))).hasSize(1);
    }
}
