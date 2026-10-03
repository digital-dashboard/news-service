package com.j11a.argus.feed.api;

import jakarta.validation.constraints.NotNull;

public record PatchFeedRequest(@NotNull Boolean enabled) {
}
