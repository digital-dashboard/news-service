package com.j11a.argus.ingest.dedup;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ExistingArticle(long id, String guidKey, @Nullable String linkKey,
                              @Nullable Instant updatedAtUpstream,
                              boolean linkedToFeed, @Nullable String feedContentHash) {}
