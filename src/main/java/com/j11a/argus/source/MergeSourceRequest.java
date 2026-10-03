package com.j11a.argus.source;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MergeSourceRequest(@NotNull @Positive Long targetSourceId) {
}
