package com.j11a.argus.feed;

public record FeedSummary(long id, String name, Topic topic) {

    public static FeedSummary of(Feed f) {
        return new FeedSummary(f.getId(), f.getName(), f.getTopic());
    }
}
