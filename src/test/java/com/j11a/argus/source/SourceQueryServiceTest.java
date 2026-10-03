package com.j11a.argus.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class SourceQueryServiceTest {

    private final SourceRepository sourceRepository = mock(SourceRepository.class);
    private final FeedRepository feedRepository = mock(FeedRepository.class);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
    private final JdbcClient.MappedQuerySpec querySpec = mock(JdbcClient.MappedQuerySpec.class);

    private SourceQueryService service;

    @BeforeEach
    void setUp() {
        service = new SourceQueryService(sourceRepository, feedRepository, jdbc);
    }

    private Source mockSource(long id, String key, String name, String homepage, String country) {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(id);
        when(source.getKey()).thenReturn(key);
        when(source.getName()).thenReturn(name);
        when(source.getHomepageUrl()).thenReturn(homepage);
        when(source.getCountry()).thenReturn(country);
        return source;
    }

    private Feed mockFeed(long id, Source source, String name, Topic topic) {
        Feed feed = mock(Feed.class);
        when(feed.getId()).thenReturn(id);
        when(feed.getSource()).thenReturn(source);
        when(feed.getName()).thenReturn(name);
        when(feed.getTopic()).thenReturn(topic);
        return feed;
    }

    @Test
    void listReturnsPagedSourcesWithFeedsAndArticleCounts() {
        Source s1 = mockSource(1L, "bbc.co.uk", "BBC", "https://bbc.co.uk", "GB");
        Source s2 = mockSource(2L, "empty.org", "Empty", null, null);

        PageRequest request = PageRequest.of(0, 20, Sort.by("id").ascending());
        when(sourceRepository.findAll(request)).thenReturn(new PageImpl<>(List.of(s1, s2), request, 2));

        Feed f1 = mockFeed(10L, s1, "BBC News", Topic.NEWS);
        Feed f2 = mockFeed(20L, s1, "BBC Sport", Topic.SPORT);
        when(feedRepository.findBySourceIdInOrderByIdAsc(List.of(1L, 2L))).thenReturn(List.of(f1, f2));

        when(jdbc.sql(any(String.class))).thenReturn(spec);
        when(spec.param(eq("ids"), any())).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(querySpec);
        when(querySpec.stream()).thenReturn(java.util.stream.Stream.of(java.util.Map.entry(1L, 42L)));

        Page<SourceResponse> page = service.list(0, 20);

        assertThat(page.getTotalElements()).isEqualTo(2);
        List<SourceResponse> content = page.getContent();
        assertThat(content).hasSize(2);

        SourceResponse r1 = content.get(0);
        assertThat(r1.id()).isEqualTo(1L);
        assertThat(r1.key()).isEqualTo("bbc.co.uk");
        assertThat(r1.name()).isEqualTo("BBC");
        assertThat(r1.homepage()).isEqualTo("https://bbc.co.uk");
        assertThat(r1.country()).isEqualTo("GB");
        assertThat(r1.articleCount()).isEqualTo(42L);
        assertThat(r1.feeds()).hasSize(2);
        assertThat(r1.feeds().get(0).id()).isEqualTo(10L);
        assertThat(r1.feeds().get(1).id()).isEqualTo(20L);

        SourceResponse r2 = content.get(1);
        assertThat(r2.id()).isEqualTo(2L);
        assertThat(r2.key()).isEqualTo("empty.org");
        assertThat(r2.articleCount()).isZero();
        assertThat(r2.feeds()).isEmpty();
    }

    @Test
    void getReturnsSourceOrThrows404() {
        Source s1 = mockSource(1L, "bbc.co.uk", "BBC", "https://bbc.co.uk", "GB");
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(s1));
        when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

        when(feedRepository.findBySourceIdInOrderByIdAsc(List.of(1L))).thenReturn(List.of());
        when(jdbc.sql(any(String.class))).thenReturn(spec);
        when(spec.param(eq("ids"), any())).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(querySpec);
        when(querySpec.stream()).thenReturn(java.util.stream.Stream.empty());

        SourceResponse res = service.get(1L);
        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.articleCount()).isZero();

        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND);
                });
    }
}
