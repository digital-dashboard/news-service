package com.j11a.argus.ingest.dedup;

import com.j11a.argus.feed.parse.ParsedEntry;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record KeyedEntry(ParsedEntry entry, String guidKey, @Nullable String linkKey,
                         String contentHash, Instant effectiveAt, int position) {}
