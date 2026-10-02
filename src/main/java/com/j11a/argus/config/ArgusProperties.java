package com.j11a.argus.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("argus")
public record ArgusProperties(
        @Valid @NotNull Db db,
        @Valid @NotNull Admin admin,
        @Valid @NotNull Telemetry telemetry) {

    private static final String MASK = "***";

    public record Db(@NotBlank String url, @NotBlank String username, @NotBlank String password) {

        @Override
        public String toString() {
            return "Db[url=" + url + ", username=" + username + ", password=" + MASK + "]";
        }
    }

    public record Admin(@NotBlank String key) {

        public static final int MIN_KEY_LENGTH = 32;

        // Not @Size: the binder's failure report prints the rejected value, which would print a short key.
        @AssertTrue(message = "argus.admin.key must be at least " + MIN_KEY_LENGTH + " characters")
        boolean isKeyLongEnough() {
            return key == null || key.length() >= MIN_KEY_LENGTH;
        }

        @Override
        public String toString() {
            return "Admin[key=" + MASK + "]";
        }
    }

    public record Telemetry(
            @NotBlank
            @Pattern(regexp = "^https?://[^/\\s]+$",
                    message = "argus.telemetry.otlp-base-url must be a base URL without a path, such as"
                            + " http://tempo:4318; Argus appends /v1/traces")
            String otlpBaseUrl,
            @DecimalMin("0.0") @DecimalMax("1.0") double samplingProbability) {
    }
}
