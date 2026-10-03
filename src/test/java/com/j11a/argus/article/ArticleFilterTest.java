package com.j11a.argus.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.Topic;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArticleFilterTest {

    @Test
    void nullCollectionsDefaultToEmptySets() {
        ArticleFilter filter = new ArticleFilter(null, null);

        assertThat(filter.topics()).isEmpty();
        assertThat(filter.countries()).isEmpty();
    }

    @Test
    void filterKeepsTheTopicAndCountrySetsItWasGiven() {
        ArticleFilter filter = new ArticleFilter(Set.of(Topic.NEWS), Set.of("CA"));

        assertThat(filter.topics()).containsExactly(Topic.NEWS);
        assertThat(filter.countries()).containsExactly("CA");
    }
}
