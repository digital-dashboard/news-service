package com.j11a.argus.article;

import com.j11a.argus.feed.Topic;
import java.util.Set;

public record ArticleFilter(Set<Topic> topics, Set<String> countries) {

    public ArticleFilter {
        topics = topics == null ? Set.of() : Set.copyOf(topics);
        countries = countries == null ? Set.of() : Set.copyOf(countries);
    }
}
