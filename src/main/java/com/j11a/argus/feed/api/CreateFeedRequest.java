package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Topic;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record CreateFeedRequest(
        @NotBlank @Size(max = 2048) @AbsoluteHttpUrl String url,
        @Size(max = 255) @Nullable String name,
        @NotNull Topic topic) {
}
