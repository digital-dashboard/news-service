package com.j11a.argus.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedSummary;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.Source;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArticleResponseTest {

    private static Article articleWithExcerpt(String excerpt) {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(4L);
        when(source.getName()).thenReturn("Harbour Times");
        Article article = mock(Article.class);
        when(article.getId()).thenReturn(11L);
        when(article.getTitle()).thenReturn("Ferry service resumes");
        when(article.getExcerpt()).thenReturn(excerpt);
        when(article.getCategories()).thenReturn(List.of("travel"));
        when(article.getSource()).thenReturn(source);
        when(article.getFeeds()).thenReturn(Set.of());
        return article;
    }

    @Test
    void anArticleWithoutAnExcerptIsPresentedWithAnEmptyOne() {
        ArticleResponse response = ArticleResponse.of(articleWithExcerpt(null));

        assertThat(response.excerpt()).isEmpty();
        assertThat(response.title()).isEqualTo("Ferry service resumes");
    }

    @Test
    void anArticleWithAnExcerptKeepsIt() {
        ArticleResponse response = ArticleResponse.of(articleWithExcerpt("Crossings restart."));

        assertThat(response.excerpt()).isEqualTo("Crossings restart.");
        assertThat(response.source().name()).isEqualTo("Harbour Times");
        assertThat(response.categories()).containsExactly("travel");
    }

    @Test
    void feedsAreSortedById() {
        Feed feed1 = mock(Feed.class);
        when(feed1.getId()).thenReturn(42L);
        when(feed1.getName()).thenReturn("Z Feed");
        when(feed1.getTopic()).thenReturn(Topic.NEWS);

        Feed feed2 = mock(Feed.class);
        when(feed2.getId()).thenReturn(7L);
        when(feed2.getName()).thenReturn("A Feed");
        when(feed2.getTopic()).thenReturn(Topic.SPORT);

        Article article = articleWithExcerpt("excerpt");
        when(article.getFeeds()).thenReturn(Set.of(feed1, feed2));

        ArticleResponse response = ArticleResponse.of(article);

        assertThat(response.feeds()).extracting(FeedSummary::id).containsExactly(7L, 42L);
        assertThat(response.feeds()).extracting(FeedSummary::name).containsExactly("A Feed", "Z Feed");
    }
}
