package com.j11a.argus.article;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.Topic;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

public final class ArticleSpecifications {

    private ArticleSpecifications() {
    }

    public static Specification<Article> topicIn(@Nullable Collection<Topic> topics) {
        if (topics == null || topics.isEmpty()) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<Article> inner = subquery.correlate(root);
            Join<Article, Feed> feeds = inner.join("feeds");
            subquery.select(cb.literal(1))
                    .where(feeds.get("topic").in(topics));
            return cb.exists(subquery);
        };
    }

    public static Specification<Article> countryIn(@Nullable Collection<String> countries) {
        if (countries == null || countries.isEmpty()) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> root.get("source").get("country").in(countries);
    }
}
