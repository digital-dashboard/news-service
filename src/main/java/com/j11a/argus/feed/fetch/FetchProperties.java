package com.j11a.argus.feed.fetch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("argus.fetch")
public record FetchProperties(
        @NotBlank String userAgent,
        @NotNull @DefaultValue("5MB") DataSize maxBodySize,
        @Min(0) @Max(10) @DefaultValue("5") int maxRedirects,
        @Valid @NotNull @DefaultValue Retry retry) {

    public static final Retry DEFAULT_RETRY = new Retry(2, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(25));

    public record Retry(
            @Min(0) @Max(10) @DefaultValue("2") int maxRetries,
            @NotNull @DefaultValue("1s") Duration delay,
            @DecimalMin("1.0") @DefaultValue("2.0") double multiplier,
            @NotNull @DefaultValue("25s") Duration timeout) {
    }

    // @Positive has no validator for DataSize.
    @AssertTrue(message = "argus.fetch.max-body-size must be positive")
    boolean isMaxBodySizePositive() {
        return maxBodySize == null || maxBodySize.toBytes() > 0;
    }
}
