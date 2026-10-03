package com.j11a.argus.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.Source;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

class ArticleQueryServiceTest {

    @Test
    void listExecutesFindAllWithNewestFirstAndMapsResponses() {
        ArticleRepository repository = mock(ArticleRepository.class);
        ArticleQueryService service = new ArticleQueryService(repository);

        Source source = mock(Source.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("Test Source");

        Article article = mock(Article.class);
        when(article.getId()).thenReturn(10L);
        when(article.getTitle()).thenReturn("Title");
        when(article.getExcerpt()).thenReturn("Excerpt");
        when(article.getCategories()).thenReturn(List.of());
        when(article.getSource()).thenReturn(source);
        when(article.getFeeds()).thenReturn(Set.of());

        PageRequest expectedPageable = PageRequest.of(0, 10,
                Sort.by(Sort.Direction.DESC, "effectiveAt").and(Sort.by(Sort.Direction.DESC, "id")));

        when(repository.findAll(any(Specification.class), eq(expectedPageable)))
                .thenReturn(new PageImpl<>(List.of(article), expectedPageable, 1));

        ArticleFilter filter = new ArticleFilter(Set.of(Topic.NEWS), Set.of("CA"));
        Page<ArticleResponse> result = service.list(filter, 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).title()).isEqualTo("Title");
        verify(repository).findAll(any(Specification.class), eq(expectedPageable));
    }
}
