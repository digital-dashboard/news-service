package com.j11a.argus.ingest.dedup;

import com.j11a.argus.feed.parse.ParsedEntry;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record DedupInput(long feedId, List<ParsedEntry> entries, @Nullable String homepageKey,
                         Instant fetchedAt, ExistingArticleLookup lookup) {}
