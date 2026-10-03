package com.j11a.argus.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.Topic;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

class ArticleSpecificationsTest {

    @Test
    void emptyOrNullTopicGivesUnrestricted() {
        assertThat(ArticleSpecifications.topicIn(null)).isEqualTo(Specification.unrestricted());
        assertThat(ArticleSpecifications.topicIn(List.of())).isEqualTo(Specification.unrestricted());
        assertThat(ArticleSpecifications.topicIn(Set.of())).isEqualTo(Specification.unrestricted());
    }

    @Test
    void emptyOrNullCountryGivesUnrestricted() {
        assertThat(ArticleSpecifications.countryIn(null)).isEqualTo(Specification.unrestricted());
        assertThat(ArticleSpecifications.countryIn(List.of())).isEqualTo(Specification.unrestricted());
        assertThat(ArticleSpecifications.countryIn(Set.of())).isEqualTo(Specification.unrestricted());
    }

    @Test
    @SuppressWarnings("unchecked")
    void topicInBuildsExistsSubqueryOverInnerJoinFeeds() {
        Specification<Article> spec = ArticleSpecifications.topicIn(List.of(Topic.NEWS));

        Root<Article> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Subquery<Integer> subquery = mock(Subquery.class);
        Root<Article> inner = mock(Root.class);
        Join<Article, Feed> feeds = mock(Join.class);
        Path<Topic> topicPath = mock(Path.class);
        Predicate inPredicate = mock(Predicate.class);
        Predicate existsPredicate = mock(Predicate.class);

        when(query.subquery(Integer.class)).thenReturn(subquery);
        when(subquery.correlate(root)).thenReturn(inner);
        when(inner.<Article, Feed>join("feeds")).thenReturn(feeds);
        when(feeds.<Topic>get("topic")).thenReturn(topicPath);
        when(topicPath.in(any(List.class))).thenReturn(inPredicate);
        when(subquery.select(any())).thenReturn(subquery);
        when(subquery.where(inPredicate)).thenReturn(subquery);
        when(cb.exists(subquery)).thenReturn(existsPredicate);

        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(existsPredicate);
        verify(inner).join("feeds");
        verify(cb).exists(subquery);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countryInBuildsInPredicateOverSourceCountry() {
        Specification<Article> spec = ArticleSpecifications.countryIn(List.of("CA", "GB"));

        Root<Article> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> sourcePath = mock(Path.class);
        Path<String> countryPath = mock(Path.class);
        Predicate inPredicate = mock(Predicate.class);

        when(root.get("source")).thenReturn(sourcePath);
        when(sourcePath.<String>get("country")).thenReturn(countryPath);
        when(countryPath.in(any(List.class))).thenReturn(inPredicate);

        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(inPredicate);
        verify(root).get("source");
        verify(sourcePath).get("country");
    }
}
