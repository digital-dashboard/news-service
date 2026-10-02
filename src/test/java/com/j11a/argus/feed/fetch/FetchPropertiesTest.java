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
    }

    @Test
    void acceptsValidProperties() {
        assertThat(VALIDATOR.validate(new FetchProperties("Argus/1", DataSize.ofMegabytes(1), 10))).isEmpty();
        assertThat(VALIDATOR.validate(new FetchProperties("Argus/1", DataSize.ofBytes(1), 0))).isEmpty();
    }

    @Test
    void aMissingBodySizeFailsOnlyTheNotNullConstraint() {
        assertThat(VALIDATOR.validate(new FetchProperties("a", null, 5)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getPropertyPath()).hasToString("maxBodySize"));
    }

    @Test
    void aNegativeBodySizeIsRejectedWithTheBodySizeMessage() {
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofBytes(-1), 5)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .isEqualTo("argus.fetch.max-body-size must be positive"));
    }

    @Test
    void rejectsBlankUserAgentNonPositiveSizeAndOutOfRangeRedirects() {
        assertThat(VALIDATOR.validate(new FetchProperties(" ", DataSize.ofMegabytes(1), 5))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofBytes(0), 5))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), -1))).hasSize(1);
        assertThat(VALIDATOR.validate(new FetchProperties("a", DataSize.ofMegabytes(1), 11))).hasSize(1);
    }
}
