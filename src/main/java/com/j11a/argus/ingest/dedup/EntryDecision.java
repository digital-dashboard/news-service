package com.j11a.argus.ingest.dedup;

import com.j11a.argus.feed.parse.ParsedEntry;

public sealed interface EntryDecision {
    record Insert(KeyedEntry entry) implements EntryDecision {}
    /** The article content is rewritten only for CONTENT_CHANGED. */
    record Update(KeyedEntry entry, long articleId, UpdateReason reason, boolean guidReplaced,
                  boolean wasLinked) implements EntryDecision {}
    record Link(KeyedEntry entry, long articleId, boolean guidReplaced) implements EntryDecision {}
    record Unchanged(KeyedEntry entry, long articleId, boolean guidReplaced,
                     boolean backfillFeedHash) implements EntryDecision {}
    record Skip(ParsedEntry entry, SkipReason reason) implements EntryDecision {}
}
