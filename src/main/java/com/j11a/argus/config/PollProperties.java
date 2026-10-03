package com.j11a.argus.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("argus.poll")
public record PollProperties(
        @NotBlank @DefaultValue("0 */15 * * * *") String cron,
        @Min(1) @Max(64) @DefaultValue("8") int concurrency,
        @Min(1) @DefaultValue("3") int failingThreshold) {
}
