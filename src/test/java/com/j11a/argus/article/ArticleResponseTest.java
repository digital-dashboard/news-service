package com.j11a.argus.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.source.Source;
import java.util.List;
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
}
